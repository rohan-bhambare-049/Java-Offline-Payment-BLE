package com.pay.offline.mesh.bluetooth;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reassembles the frames of one packet, in any order.
 *
 * Bluetooth does not guarantee that frames arrive in order, and
 * a peer may resend a frame it thinks was lost. This buffer
 * therefore stores chunks by index, ignores a duplicate chunk
 * for an index it already holds, and only completes when every
 * index from zero to total minus one is present. At that point
 * it concatenates the chunks in index order and returns the
 * complete packet JSON bytes.
 *
 * A buffer that never completes must not live forever. The node
 * prunes buffers older than its reassembly timeout, so a peer
 * that walks away mid transfer does not leak memory on the
 * receiving phone.
 */
public class BleReassemblyBuffer {

    private final UUID packetId;
    private final int totalChunks;
    private final long createdAtMillis;
    private final Map<Integer, byte[]> chunks = new HashMap<>();

    public BleReassemblyBuffer(UUID packetId, int totalChunks, long createdAtMillis) {
        this.packetId = packetId;
        this.totalChunks = totalChunks;
        this.createdAtMillis = createdAtMillis;
    }

    public UUID getPacketId() {
        return packetId;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public boolean isExpired(long nowMillis, long timeoutMillis) {
        return nowMillis - createdAtMillis > timeoutMillis;
    }

    /**
     * Adds one chunk. Returns the complete reassembled bytes when
     * this chunk completes the packet, otherwise null. A chunk
     * for a different total count than the buffer was created
     * with is ignored, because it cannot belong to this packet.
     */
    public byte[] add(BleFrame frame) {
        if (frame.totalChunks() != totalChunks) {
            return null;
        }
        chunks.putIfAbsent(frame.chunkIndex(), frame.payload());
        if (chunks.size() != totalChunks) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int index = 0; index < totalChunks; index++) {
            byte[] payload = chunks.get(index);
            if (payload == null) {
                return null;
            }
            out.write(payload, 0, payload.length);
        }
        return out.toByteArray();
    }

    public int receivedChunkCount() {
        return chunks.size();
    }
}
