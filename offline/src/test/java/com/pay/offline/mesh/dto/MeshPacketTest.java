package com.pay.offline.mesh.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshPacketTest {

    @Test
    void forwardDecrementsTtlAndIncrementsHopCount() {
        MeshPacket packet = MeshPacket.create("sender-phone", "ciphertext", 5);
        MeshPacket forwarded = packet.forward();
        assertEquals(4, forwarded.ttl());
        assertEquals(1, forwarded.hopCount());
        assertEquals(packet.packetId(), forwarded.packetId());
        assertEquals(packet.ciphertext(), forwarded.ciphertext());
    }

    @Test
    void packetWithZeroTtlCannotForwardButCanStillBeHeld() {
        MeshPacket packet = MeshPacket.create("sender-phone", "ciphertext", 0);
        assertFalse(packet.canForward());
        assertThrows(IllegalStateException.class, packet::forward);
    }

    @Test
    void createUsesTtlFiveByDefault() {
        MeshPacket packet = MeshPacket.create("sender-phone", "ciphertext");
        assertEquals(5, packet.ttl());
        assertEquals(0, packet.hopCount());
        assertTrue(packet.canForward());
    }
}
