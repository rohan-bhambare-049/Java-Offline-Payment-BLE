package com.pay.offline.mesh.bluetooth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BleFrameTest {

    @Test
    void encodeDecodeRoundTrip() {
        UUID packetId = UUID.randomUUID();
        byte[] payload = "hello bluetooth".getBytes(StandardCharsets.UTF_8);
        BleFrame frame = new BleFrame(packetId, 3, 1, payload);
        BleFrame decoded = BleFrame.decode(frame.encode());
        assertEquals(packetId, decoded.packetId());
        assertEquals(3, decoded.totalChunks());
        assertEquals(1, decoded.chunkIndex());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void tamperedPayloadFailsCrcCheck() {
        BleFrame frame = new BleFrame(
                UUID.randomUUID(), 1, 0, "payload".getBytes(StandardCharsets.UTF_8)
        );
        byte[] wire = frame.encode();
        wire[wire.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> BleFrame.decode(wire));
    }

    @Test
    void frameSizeLimitIsEnforced() {
        byte[] tooBig = new byte[MeshGattConstants.MAX_FRAME_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> BleFrame.decode(tooBig));
    }

    @Test
    void reassemblyWorksOutOfOrderWithDuplicateChunks() {
        UUID packetId = UUID.randomUUID();
        byte[] first = "first-".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        BleReassemblyBuffer buffer = new BleReassemblyBuffer(packetId, 2, 1000L);
        // Second chunk first, then a duplicate of it, then the first chunk.
        assertEquals(null, buffer.add(new BleFrame(packetId, 2, 1, second)));
        assertEquals(null, buffer.add(new BleFrame(packetId, 2, 1, second)));
        assertEquals(1, buffer.receivedChunkCount());
        byte[] complete = buffer.add(new BleFrame(packetId, 2, 0, first));
        assertArrayEquals(
                "first-second".getBytes(StandardCharsets.UTF_8),
                complete
        );
    }

    @Test
    void expiredBufferIsDetected() {
        BleReassemblyBuffer buffer = new BleReassemblyBuffer(UUID.randomUUID(), 2, 1000L);
        assertEquals(false, buffer.isExpired(1000L + 30_000L, 60_000L));
        assertEquals(true, buffer.isExpired(1000L + 60_001L, 60_000L));
    }
}
