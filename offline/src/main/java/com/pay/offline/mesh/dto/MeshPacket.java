package com.pay.offline.mesh.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * The wire format that travels phone to phone.
 *
 * The outer fields are readable by any intermediate phone: packet id,
 * origin device id, creation time, TTL, hop count. That is
 * deliberate. An intermediate needs the packet id for duplicate
 * suppression and the TTL for forwarding, and it needs nothing else.
 *
 * The payment itself is only inside ciphertext, encrypted for the
 * server. There is deliberately no packet hash field here. The
 * server computes SHA256 over the ciphertext itself on arrival,
 * because a hash supplied by an untrusted intermediate could simply
 * be rewritten. Never trust a hash that arrived with the packet.
 *
 * TTL rules:
 * - A packet with TTL 0 is not forwarded again. It may still be
 *   uploaded by the phone that already holds it, because holding a
 *   packet and forwarding it are different actions.
 * - Every forward creates a new MeshPacket with TTL one lower and
 *   hop count one higher. The ciphertext never changes while
 *   forwarding, so every copy of the same payment has the same
 *   ciphertext hash on the server.
 */
public record MeshPacket(

        @NotNull
        UUID packetId,

        @NotBlank
        String originDeviceId,

        @PositiveOrZero
        long createdAt,

        @Min(0)
        int ttl,

        @PositiveOrZero
        int hopCount,

        @NotBlank
        String ciphertext

) {

    public static MeshPacket create(
            String originDeviceId,
            String ciphertext
    ) {
        return create(originDeviceId, ciphertext, 5);
    }

    public static MeshPacket create(
            String originDeviceId,
            String ciphertext,
            int ttl
    ) {
        if (ttl < 0) {
            throw new IllegalArgumentException("TTL cannot be negative");
        }
        return new MeshPacket(
                UUID.randomUUID(),
                originDeviceId,
                System.currentTimeMillis(),
                ttl,
                0,
                ciphertext
        );
    }

    public boolean canForward() {
        return ttl > 0;
    }

    public MeshPacket forward() {

        if (ttl <= 0) {
            throw new IllegalStateException(
                    "Packet cannot be forwarded because TTL has expired"
            );
        }

        return new MeshPacket(
                packetId,
                originDeviceId,
                createdAt,
                ttl - 1,
                hopCount + 1,
                ciphertext
        );
    }
}
