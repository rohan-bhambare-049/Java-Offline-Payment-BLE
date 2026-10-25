package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real mesh node, as it runs on a phone.
 *
 * This class contains no Bluetooth API calls and no Spring
 * code. It contains the node behaviour that is the same on
 * every phone: receive frames, reassemble packets, drop
 * duplicates, store packets, forward with TTL, and upload when
 * internet is available. The radio is supplied through
 * MeshTransport, and the upload is supplied through
 * MeshBridgeUploader. That split is deliberate: it is how a
 * real Bluetooth app is structured, and it is what allows this
 * class to be compiled and tested on a JVM without a radio.
 *
 * Behaviour, in order, for a packet arriving over Bluetooth:
 * - Frames are decoded and CRC checked. A bad frame is dropped
 *   silently, because a single bad frame must not kill a node.
 * - Frames are reassembled by packet id, in any order, with
 *   duplicate chunks ignored.
 * - When a packet is complete, its packet id is checked against
 *   the seen set. A packet id already seen is dropped, not
 *   stored again, and not forwarded again. This is the device
 *   level duplicate suppression that stops gossip loops.
 * - A new packet is stored, so the phone can carry it and can
 *   upload it later if internet appears.
 * - If the phone has internet and an uploader is present, the
 *   packet is uploaded immediately. A terminal server outcome,
 *   settled, duplicate dropped, rejected, expired, or invalid,
 *   ends the packet on this phone: it is removed from storage
 *   and not forwarded. An upload exception, for example no
 *   network after all, falls through to forwarding, so the
 *   packet still has a chance to reach another bridge.
 * - If there is no internet, or no uploader, or the upload
 *   failed, the packet is forwarded if its TTL is above zero,
 *   as a new packet with TTL minus one and hop count plus one,
 *   with the ciphertext unchanged. A packet with TTL zero is
 *   stored but not forwarded. It can still be uploaded by this
 *   phone, because holding a packet and forwarding it are
 *   different actions.
 *
 * A node that originates a packet uses sendPacket, which stores
 * the packet, marks it seen, and broadcasts its frames. It does
 * not forward its own packet with a reduced TTL: the first hop
 * happens when a peer receives it and forwards it.
 *
 * Reassembly buffers expire after 60 seconds by default, via
 * pruneExpiredBuffers. The Android app should call that
 * periodically, for example on a timer or whenever a new frame
 * arrives.
 */
public class MeshNode implements MeshFrameListener {

    public static final long REASSEMBLY_TIMEOUT_MILLIS = 60_000L;

    private final String deviceId;
    private final MeshTransport transport;
    private final MeshBridgeUploader bridgeUploader;
    private final MeshPacketJsonCodec packetCodec = new MeshPacketJsonCodec();

    private final Set<UUID> seenPacketIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, MeshPacket> storedPackets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BleReassemblyBuffer> reassemblyBuffers =
            new ConcurrentHashMap<>();

    private volatile boolean hasInternet;

    public MeshNode(
            String deviceId,
            MeshTransport transport,
            MeshBridgeUploader bridgeUploader,
            boolean hasInternet
    ) {
        this.deviceId = deviceId;
        this.transport = transport;
        this.bridgeUploader = bridgeUploader;
        this.hasInternet = hasInternet;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean hasInternet() {
        return hasInternet;
    }

    public void setHasInternet(boolean hasInternet) {
        this.hasInternet = hasInternet;
    }

    public void start() {
        transport.start(this);
    }

    public void stop() {
        transport.stop();
    }

    /**
     * Originates a packet on this phone. Returns false if this
     * packet id was already seen, which should not happen for a
     * freshly created packet.
     */
    public boolean sendPacket(MeshPacket packet) {
        if (!seenPacketIds.add(packet.packetId())) {
            return false;
        }
        storedPackets.put(packet.packetId(), packet);
        sendFrames(packet);
        return true;
    }

    @Override
    public void onFrameReceived(byte[] frame, String peerAddress) {
        BleFrame decoded;
        try {
            decoded = BleFrame.decode(frame);
        } catch (IllegalArgumentException exception) {
            return;
        }
        BleReassemblyBuffer buffer = reassemblyBuffers.computeIfAbsent(
                decoded.packetId(),
                id -> new BleReassemblyBuffer(id, decoded.totalChunks(), System.currentTimeMillis())
        );
        byte[] complete = buffer.add(decoded);
        if (complete == null) {
            return;
        }
        reassemblyBuffers.remove(decoded.packetId());
        MeshPacket packet;
        try {
            packet = packetCodec.fromJson(new String(complete, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return;
        }
        handlePacket(packet);
    }

    /**
     * Handles one complete packet. Returns true if the packet
     * was new, false if it was a duplicate.
     */
    public boolean handlePacket(MeshPacket packet) {
        if (!seenPacketIds.add(packet.packetId())) {
            return false;
        }
        storedPackets.put(packet.packetId(), packet);

        if (hasInternet && bridgeUploader != null) {
            try {
                MeshIngestionResult result = bridgeUploader.upload(packet);
                if (isTerminal(result.outcome())) {
                    storedPackets.remove(packet.packetId());
                }
                return true;
            } catch (RuntimeException exception) {
                // Upload failed, for example the network dropped
                // again. Fall through to forwarding below, so the
                // packet still moves towards another bridge.
            }
        }

        if (packet.canForward()) {
            sendFrames(packet.forward());
        }
        return true;
    }

    /**
     * Uploads every stored packet, for example when the phone
     * regains internet. Terminal packets are removed from
     * storage. Packets whose upload throws are kept, for a
     * later retry and for forwarding.
     */
    public List<MeshIngestionResult> uploadAllStored() {
        List<MeshIngestionResult> results = new ArrayList<>();
        if (!hasInternet || bridgeUploader == null) {
            return results;
        }
        for (MeshPacket packet : new ArrayList<>(storedPackets.values())) {
            try {
                MeshIngestionResult result = bridgeUploader.upload(packet);
                results.add(result);
                if (isTerminal(result.outcome())) {
                    storedPackets.remove(packet.packetId());
                }
            } catch (RuntimeException exception) {
                // Keep the packet for a later retry.
            }
        }
        return results;
    }

    public List<MeshPacket> storedPackets() {
        return new ArrayList<>(storedPackets.values());
    }

    public int storedPacketCount() {
        return storedPackets.size();
    }

    public int seenPacketCount() {
        return seenPacketIds.size();
    }

    public void pruneExpiredBuffers(long nowMillis) {
        reassemblyBuffers.entrySet().removeIf(entry ->
                entry.getValue().isExpired(nowMillis, REASSEMBLY_TIMEOUT_MILLIS));
    }

    private boolean isTerminal(MeshIngestionOutcome outcome) {
        return outcome == MeshIngestionOutcome.SETTLED
                || outcome == MeshIngestionOutcome.DUPLICATE_DROPPED
                || outcome == MeshIngestionOutcome.REJECTED
                || outcome == MeshIngestionOutcome.EXPIRED
                || outcome == MeshIngestionOutcome.INVALID;
    }

    private void sendFrames(MeshPacket packet) {
        byte[] json = packetCodec.toJson(packet).getBytes(StandardCharsets.UTF_8);
        int totalChunks = (json.length + BleFrame.MAX_PAYLOAD_BYTES - 1)
                / BleFrame.MAX_PAYLOAD_BYTES;
        for (int index = 0; index < totalChunks; index++) {
            int start = index * BleFrame.MAX_PAYLOAD_BYTES;
            int end = Math.min(json.length, start + BleFrame.MAX_PAYLOAD_BYTES);
            byte[] slice = new byte[end - start];
            System.arraycopy(json, start, slice, 0, slice.length);
            BleFrame frame = new BleFrame(packet.packetId(), totalChunks, index, slice);
            transport.sendFrame(frame.encode());
        }
    }
}
