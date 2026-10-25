package com.pay.offline.mesh.crypto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The SHA layer of the mesh system.
 *
 * It is used in two places, and the two uses must not be confused.
 *
 * First, the outer hash: SHA256 over the raw decoded ciphertext bytes.
 * This is the idempotency key for a packet. Two deliveries of the same
 * packet have byte identical ciphertext, so they have the same outer hash,
 * even if an intermediate rewrote the packet id. A tampered packet has a
 * different hash, so it is not mistaken for the original packet.
 *
 * Second, the inner hash: SHA256 over a canonical string built from the
 * clear payment fields. The sender puts this hash inside the encrypted
 * payload. After decryption the server rebuilds the same string and
 * compares the hash. A mismatch means the payload fields do not belong
 * together, even if the packet somehow passed every earlier check.
 *
 * Both hashes are plain SHA256. Neither hash is a signature and neither
 * hash makes a packet secret. Secrecy comes from the hybrid encryption
 * in HybridCryptoService, and tamper evidence comes from AES GCM.
 */
public final class MeshHash {

    private MeshHash() {
    }

    public static String sha256Hex(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Canonical payment hash. The amount is stripped of trailing zeros
     * first, so 500.00 and 500.0 hash to the same value. Field order is
     * fixed, and the separator is a single pipe character.
     */
    public static String paymentHash(
            UUID paymentId,
            String senderAccountNumber,
            String receiverAccountNumber,
            BigDecimal amount,
            String currency,
            long createdAt
    ) {
        String canonical = paymentId + "|"
                + senderAccountNumber + "|"
                + receiverAccountNumber + "|"
                + amount.stripTrailingZeros().toPlainString() + "|"
                + currency + "|"
                + createdAt;
        return sha256Hex(canonical);
    }
}
