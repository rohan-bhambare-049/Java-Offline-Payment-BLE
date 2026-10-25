package com.pay.offline.mesh;

import com.pay.offline.mesh.crypto.HybridCryptoService;
import com.pay.offline.mesh.crypto.MeshHash;
import com.pay.offline.mesh.crypto.ServerKeyHolder;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;
import com.pay.offline.mesh.dto.MeshPaymentPayload;
import com.pay.offline.mesh.repository.MeshPacketReceiptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The bridge upload pipeline, in the same order as the reference
 * project, adapted to this project database.
 *
 * 1. Hash the ciphertext with SHA256. The hash is computed over
 *    the raw decoded bytes, not over the Base64 text, so a
 *    re-encoded copy of the same packet still hashes the same.
 * 2. Claim the hash in memory. A loser is dropped as a duplicate
 *    before any RSA work. This is the duplicate storm defence.
 * 3. Decrypt with the server private key. AES GCM failure means
 *    the packet was tampered with, or encrypted for a different
 *    key. Either way the packet is invalid and the claim is
 *    released, because a tampered packet must not block its hash
 *    forever. A tampered packet has a different hash from the
 *    original packet anyway.
 * 4. Parse the payload JSON and verify the inner SHA layer: the
 *    payload hash must equal MeshHash.paymentHash over the same
 *    fields. A mismatch is invalid.
 * 5. Freshness, the replay protection layer. The payload creation
 *    time must not be older than mesh.packet.maxAgeHours, and must
 *    not be more than mesh.packet.clockSkewMinutes in the future.
 *    The creation time is inside the encrypted, GCM authenticated
 *    payload, so an intermediate cannot rewind it without breaking
 *    decryption. A replayed packet is also caught earlier by the
 *    claim and by the instruction UUID, but freshness is what stops
 *    a very old captured packet from being useful at all.
 * 6. Hand the verified payload to MeshSettlementService, which
 *    runs the single database transaction and its durable
 *    idempotency checks.
 *
 * A TTL of zero does not block upload. TTL limits forwarding, not
 * delivery. A phone that already holds a packet may upload it even
 * if the packet can no longer hop further.
 *
 * This endpoint is authenticated by the existing security rules.
 * The reference demo left its bridge endpoint open. This project
 * does not, because a bridge upload moves money. A bridge phone
 * must be logged in as a user of this system, and the bridge node
 * id header is recorded for audit only, never trusted for
 * authorisation.
 */
@Service
public class MeshIngestionService {

    private final ServerKeyHolder serverKeyHolder;
    private final HybridCryptoService hybridCryptoService;
    private final MeshPayloadCodec meshPayloadCodec;
    private final MeshPacketClaimService meshPacketClaimService;
    private final MeshPacketReceiptRepository meshPacketReceiptRepository;
    private final MeshSettlementService meshSettlementService;
    private final long maxAgeHours;
    private final long clockSkewMinutes;

    public MeshIngestionService(
            ServerKeyHolder serverKeyHolder,
            HybridCryptoService hybridCryptoService,
            MeshPayloadCodec meshPayloadCodec,
            MeshPacketClaimService meshPacketClaimService,
            MeshPacketReceiptRepository meshPacketReceiptRepository,
            MeshSettlementService meshSettlementService,
            @Value("${mesh.packet.maxAgeHours:24}") long maxAgeHours,
            @Value("${mesh.packet.clockSkewMinutes:5}") long clockSkewMinutes
    ) {
        this.serverKeyHolder = serverKeyHolder;
        this.hybridCryptoService = hybridCryptoService;
        this.meshPayloadCodec = meshPayloadCodec;
        this.meshPacketClaimService = meshPacketClaimService;
        this.meshPacketReceiptRepository = meshPacketReceiptRepository;
        this.meshSettlementService = meshSettlementService;
        this.maxAgeHours = maxAgeHours;
        this.clockSkewMinutes = clockSkewMinutes;
    }

    public MeshIngestionResult ingest(MeshPacket packet, String bridgeNodeId) {
        if (packet == null
                || packet.packetId() == null
                || packet.originDeviceId() == null
                || packet.originDeviceId().isBlank()
                || packet.ciphertext() == null
                || packet.ciphertext().isBlank()
                || packet.ttl() < 0
                || packet.hopCount() < 0
                || packet.createdAt() <= 0) {
            return MeshIngestionResult.invalid(null, "Mesh packet is incomplete");
        }

        String packetHash;
        try {
            byte[] ciphertextBytes = Base64.getDecoder().decode(packet.ciphertext());
            packetHash = MeshHash.sha256Hex(ciphertextBytes);
        } catch (IllegalArgumentException exception) {
            return MeshIngestionResult.invalid(null, "Ciphertext is not valid Base64");
        }

        if (!meshPacketClaimService.tryClaim(packetHash)) {
            String instructionUuid = meshPacketReceiptRepository
                    .findByPacketHash(packetHash)
                    .map(receipt -> receipt.getInstructionUuid())
                    .orElse(null);
            return MeshIngestionResult.duplicate(
                    packetHash,
                    instructionUuid,
                    "Packet was already claimed by an earlier bridge upload"
            );
        }

        MeshPaymentPayload payload;
        try {
            byte[] plaintext = hybridCryptoService.decrypt(
                    serverKeyHolder.getPrivateKey(),
                    packet.ciphertext()
            );
            payload = meshPayloadCodec.fromJson(
                    new String(plaintext, StandardCharsets.UTF_8)
            );
        } catch (IllegalArgumentException exception) {
            meshPacketClaimService.release(packetHash);
            return MeshIngestionResult.invalid(
                    packetHash,
                    "Packet could not be decrypted or parsed"
            );
        }

        String formatProblem = validatePayloadFormat(payload);
        if (formatProblem != null) {
            meshPacketClaimService.release(packetHash);
            return MeshIngestionResult.invalid(packetHash, formatProblem);
        }

        String expectedHash = MeshHash.paymentHash(
                payload.paymentId(),
                payload.senderAccountNumber(),
                payload.receiverAccountNumber(),
                payload.amount(),
                payload.currency(),
                payload.createdAt()
        );
        if (!expectedHash.equals(payload.payloadHash())) {
            meshPacketClaimService.release(packetHash);
            return MeshIngestionResult.invalid(
                    packetHash,
                    "Payload hash does not match the payload fields"
            );
        }

        long now = System.currentTimeMillis();
        long ageMillis = now - payload.createdAt();
        if (payload.createdAt() > now + clockSkewMinutes * 60_000L) {
            meshPacketClaimService.release(packetHash);
            return MeshIngestionResult.invalid(
                    packetHash,
                    "Packet creation time is too far in the future"
            );
        }
        if (ageMillis > maxAgeHours * 3_600_000L) {
            meshPacketClaimService.release(packetHash);
            return MeshIngestionResult.expired(
                    packetHash,
                    "Packet is older than the allowed age"
            );
        }

        try {
            return meshSettlementService.settle(packet, payload, packetHash, bridgeNodeId);
        } catch (DataIntegrityViolationException exception) {
            return MeshIngestionResult.duplicate(
                    packetHash,
                    null,
                    "Packet was already settled by a concurrent upload"
            );
        }
    }

    private String validatePayloadFormat(MeshPaymentPayload payload) {
        if (payload == null
                || payload.paymentId() == null
                || payload.senderAccountNumber() == null
                || payload.receiverAccountNumber() == null
                || payload.amount() == null
                || payload.currency() == null
                || payload.payloadHash() == null
                || payload.payloadHash().isBlank()
                || payload.createdAt() <= 0) {
            return "Payload is incomplete";
        }
        if (!payload.senderAccountNumber().matches("\\d{16}")
                || !payload.receiverAccountNumber().matches("\\d{16}")) {
            return "Account numbers must contain exactly 16 digits";
        }
        if (payload.senderAccountNumber().equals(payload.receiverAccountNumber())) {
            return "Sender and receiver accounts must be different";
        }
        if (payload.amount().signum() <= 0 || payload.amount().scale() > 2) {
            return "Payment amount is invalid";
        }
        if (!"INR".equals(payload.currency())) {
            return "Only INR payments are supported";
        }
        return null;
    }
}
