package com.pay.offline.mesh;

import com.pay.offline.mesh.crypto.HybridCryptoService;
import com.pay.offline.mesh.crypto.MeshHash;
import com.pay.offline.mesh.crypto.ServerKeyHolder;
import com.pay.offline.mesh.dto.MeshPacket;
import com.pay.offline.mesh.dto.MeshPaymentPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds a packet the way a sender phone would build it.
 *
 * On a real phone this logic lives in the Bluetooth node, in the
 * mesh bluetooth package, not on the server. It is included here
 * so the exact packet format is defined in one place and so
 * backend tests can build genuine encrypted packets without a
 * phone. The phone app will need the server public key, which it
 * fetches from GET /api/v1/mesh/server-public-key, and it will
 * need this same sequence: build payload, hash payload, serialise,
 * encrypt, wrap.
 *
 * The sender phone does not need the server private key, and it
 * must never ask for it.
 */
@Service
public class MeshPacketFactory {

    private final ServerKeyHolder serverKeyHolder;
    private final HybridCryptoService hybridCryptoService;
    private final MeshPayloadCodec meshPayloadCodec;
    private final int defaultTtl;

    public MeshPacketFactory(
            ServerKeyHolder serverKeyHolder,
            HybridCryptoService hybridCryptoService,
            MeshPayloadCodec meshPayloadCodec,
            @Value("${mesh.packet.ttl:5}") int defaultTtl
    ) {
        this.serverKeyHolder = serverKeyHolder;
        this.hybridCryptoService = hybridCryptoService;
        this.meshPayloadCodec = meshPayloadCodec;
        this.defaultTtl = defaultTtl;
    }

    public MeshPacket createPacket(
            String originDeviceId,
            String senderAccountNumber,
            String receiverAccountNumber,
            BigDecimal amount,
            String currency
    ) {
        UUID paymentId = UUID.randomUUID();
        long createdAt = System.currentTimeMillis();
        String payloadHash = MeshHash.paymentHash(
                paymentId,
                senderAccountNumber,
                receiverAccountNumber,
                amount,
                currency,
                createdAt
        );
        MeshPaymentPayload payload = new MeshPaymentPayload(
                paymentId,
                senderAccountNumber,
                receiverAccountNumber,
                amount,
                currency,
                createdAt,
                payloadHash
        );
        String ciphertext = hybridCryptoService.encrypt(
                serverKeyHolder.getPublicKey(),
                meshPayloadCodec.toJson(payload).getBytes(StandardCharsets.UTF_8)
        );
        return MeshPacket.create(originDeviceId, ciphertext, defaultTtl);
    }

    /**
     * Helper for a packet with a caller chosen payment id and
     * creation time. Used to build an old packet for the replay
     * protection check, and to rebuild the same payment with a
     * fresh id for a legitimate second payment.
     */
    public MeshPacket createPacketWithDetails(
            String originDeviceId,
            UUID paymentId,
            String senderAccountNumber,
            String receiverAccountNumber,
            BigDecimal amount,
            String currency,
            long createdAt
    ) {
        String payloadHash = MeshHash.paymentHash(
                paymentId,
                senderAccountNumber,
                receiverAccountNumber,
                amount,
                currency,
                createdAt
        );
        MeshPaymentPayload payload = new MeshPaymentPayload(
                paymentId,
                senderAccountNumber,
                receiverAccountNumber,
                amount,
                currency,
                createdAt,
                payloadHash
        );
        String ciphertext = hybridCryptoService.encrypt(
                serverKeyHolder.getPublicKey(),
                meshPayloadCodec.toJson(payload).getBytes(StandardCharsets.UTF_8)
        );
        return MeshPacket.create(originDeviceId, ciphertext, defaultTtl);
    }
}
