package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshPacketJsonCodecTest {

    @Test
    void packetJsonRoundTrip() {
        MeshPacketJsonCodec codec = new MeshPacketJsonCodec();
        MeshPacket packet = new MeshPacket(
                UUID.randomUUID(),
                "phone-a",
                1730000000000L,
                5,
                0,
                "base64ciphertext=="
        );
        assertEquals(packet, codec.fromJson(codec.toJson(packet)));
    }
}
