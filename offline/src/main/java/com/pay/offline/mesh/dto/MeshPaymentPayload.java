package com.pay.offline.mesh.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The clear payment, before encryption.
 *
 * This record is never sent over the mesh in clear form. The sender
 * serialises it to JSON, encrypts that JSON for the server, and only
 * the resulting ciphertext travels in a MeshPacket.
 *
 * paymentId is the nonce for the payment. It is generated once by
 * the sender, and it becomes the instruction UUID on the server.
 * That is what makes a replay recognisable: a replayed packet carries
 * the same paymentId and the same ciphertext, while a genuinely new
 * payment for the same amount carries a new paymentId.
 *
 * payloadHash is the inner SHA layer. It is SHA256 over the canonical
 * form of the other fields, computed by MeshHash.paymentHash. The
 * server recomputes it after decryption and rejects a mismatch.
 *
 * createdAt is epoch millis at the time the sender created the
 * payment. The server uses it for replay protection: a packet older
 * than the configured maximum age is rejected, even if its
 * ciphertext is otherwise valid.
 */
public record MeshPaymentPayload(

        @NotNull
        UUID paymentId,

        @NotBlank
        String senderAccountNumber,

        @NotBlank
        String receiverAccountNumber,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal amount,

        @NotBlank
        String currency,

        @NotNull
        @Positive
        long createdAt,

        @NotBlank
        String payloadHash

) {
}
