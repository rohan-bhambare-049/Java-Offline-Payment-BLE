package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshNodeTest {

    static class FakeTransport implements MeshTransport {
        final List<byte[]> sentFrames = new ArrayList<>();
        MeshFrameListener listener;

        @Override
        public void start(MeshFrameListener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() {
        }

        @Override
        public void sendFrame(byte[] frame) {
            sentFrames.add(frame.clone());
        }

        void injectFrame(byte[] frame) {
            listener.onFrameReceived(frame, "peer-address");
        }
    }

    static class SettledUploader implements MeshBridgeUploader {
        int calls;

        @Override
        public MeshIngestionResult upload(MeshPacket packet) {
            calls++;
            return MeshIngestionResult.settled("hash", "instruction", "transaction");
        }
    }

    static class FailingUploader implements MeshBridgeUploader {
        @Override
        public MeshIngestionResult upload(MeshPacket packet) {
            throw new IllegalStateException("No network");
        }
    }

    private MeshPacket packetWithTtl(int ttl) {
        // Ciphertext is deliberately long, so the packet must be fragmented.
        String ciphertext = "A".repeat(600);
        return new MeshPacket(
                java.util.UUID.randomUUID(),
                "phone-origin",
                System.currentTimeMillis(),
                ttl,
                0,
                ciphertext
        );
    }

    private List<byte[]> framesFor(MeshPacket packet) {
        MeshPacketJsonCodec codec = new MeshPacketJsonCodec();
        byte[] json = codec.toJson(packet).getBytes(StandardCharsets.UTF_8);
        List<byte[]> frames = new ArrayList<>();
        int total = (json.length + BleFrame.MAX_PAYLOAD_BYTES - 1) / BleFrame.MAX_PAYLOAD_BYTES;
        for (int index = 0; index < total; index++) {
            int start = index * BleFrame.MAX_PAYLOAD_BYTES;
            int end = Math.min(json.length, start + BleFrame.MAX_PAYLOAD_BYTES);
            byte[] slice = new byte[end - start];
            System.arraycopy(json, start, slice, 0, slice.length);
            frames.add(new BleFrame(packet.packetId(), total, index, slice).encode());
        }
        return frames;
    }

    private MeshPacket forwardedPacketFrom(FakeTransport transport) {
        // Reassemble the frames the node sent, in order, and parse the packet.
        java.util.Map<Integer, byte[]> chunks = new java.util.HashMap<>();
        java.util.UUID packetId = null;
        int total = 0;
        for (byte[] wire : transport.sentFrames) {
            BleFrame frame = BleFrame.decode(wire);
            packetId = frame.packetId();
            total = frame.totalChunks();
            chunks.put(frame.chunkIndex(), frame.payload());
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < total; index++) {
            out.write(chunks.get(index), 0, chunks.get(index).length);
        }
        return new MeshPacketJsonCodec().fromJson(
                new String(out.toByteArray(), StandardCharsets.UTF_8)
        );
    }

    @Test
    void offlineNodeForwardsWithTtlMinusOneAndHopPlusOne() {
        FakeTransport transport = new FakeTransport();
        MeshNode node = new MeshNode("phone-b", transport, new FailingUploader(), false);
        node.start();
        MeshPacket packet = packetWithTtl(5);
        for (byte[] frame : framesFor(packet)) {
            transport.injectFrame(frame);
        }
        assertEquals(1, node.storedPacketCount());
        assertEquals(1, node.seenPacketCount());
        MeshPacket forwarded = forwardedPacketFrom(transport);
        assertEquals(packet.packetId(), forwarded.packetId());
        assertEquals(4, forwarded.ttl());
        assertEquals(1, forwarded.hopCount());
        assertEquals(packet.ciphertext(), forwarded.ciphertext());
    }

    @Test
    void duplicatePacketIsDroppedAndNotForwardedAgain() {
        FakeTransport transport = new FakeTransport();
        MeshNode node = new MeshNode("phone-b", transport, new FailingUploader(), false);
        node.start();
        MeshPacket packet = packetWithTtl(5);
        for (byte[] frame : framesFor(packet)) {
            transport.injectFrame(frame);
        }
        int framesAfterFirst = transport.sentFrames.size();
        for (byte[] frame : framesFor(packet)) {
            transport.injectFrame(frame);
        }
        assertEquals(framesAfterFirst, transport.sentFrames.size());
        assertEquals(1, node.storedPacketCount());
    }

    @Test
    void ttlZeroPacketIsStoredButNotForwarded() {
        FakeTransport transport = new FakeTransport();
        MeshNode node = new MeshNode("phone-b", transport, new FailingUploader(), false);
        node.start();
        MeshPacket packet = packetWithTtl(0);
        for (byte[] frame : framesFor(packet)) {
            transport.injectFrame(frame);
        }
        assertEquals(1, node.storedPacketCount());
        assertEquals(0, transport.sentFrames.size());
    }

    @Test
    void onlineNodeUploadsInsteadOfForwarding() {
        FakeTransport transport = new FakeTransport();
        SettledUploader uploader = new SettledUploader();
        MeshNode node = new MeshNode("phone-bridge", transport, uploader, true);
        node.start();
        MeshPacket packet = packetWithTtl(5);
        assertTrue(node.handlePacket(packet));
        assertEquals(1, uploader.calls);
        assertEquals(0, transport.sentFrames.size());
        assertEquals(0, node.storedPacketCount());
        // A duplicate direct delivery must not upload again.
        assertFalse(node.handlePacket(packet));
        assertEquals(1, uploader.calls);
    }

    @Test
    void uploadAllStoredRemovesSettledPackets() {
        FakeTransport transport = new FakeTransport();
        SettledUploader uploader = new SettledUploader();
        MeshNode node = new MeshNode("phone-bridge", transport, uploader, false);
        node.start();
        MeshPacket packet = packetWithTtl(5);
        assertTrue(node.sendPacket(packet));
        assertEquals(1, node.storedPacketCount());
        node.setHasInternet(true);
        List<MeshIngestionResult> results = node.uploadAllStored();
        assertEquals(1, results.size());
        assertEquals(MeshIngestionOutcome.SETTLED, results.get(0).outcome());
        assertEquals(0, node.storedPacketCount());
    }
}
