package com.pay.offline.mesh.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HybridCryptoServiceTest {

    private final HybridCryptoService crypto = new HybridCryptoService();

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        KeyPair pair = keyPair();
        byte[] plaintext = "{\"amount\":500}".getBytes(StandardCharsets.UTF_8);
        String ciphertext = crypto.encrypt(pair.getPublic(), plaintext);
        assertNotEquals(new String(plaintext, StandardCharsets.UTF_8), ciphertext);
        assertArrayEquals(plaintext, crypto.decrypt(pair.getPrivate(), ciphertext));
    }

    @Test
    void samePlaintextEncryptsDifferentlyEachTime() throws Exception {
        KeyPair pair = keyPair();
        byte[] plaintext = "same payment".getBytes(StandardCharsets.UTF_8);
        String first = crypto.encrypt(pair.getPublic(), plaintext);
        String second = crypto.encrypt(pair.getPublic(), plaintext);
        assertNotEquals(first, second);
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        KeyPair pair = keyPair();
        byte[] plaintext = "do not tamper".getBytes(StandardCharsets.UTF_8);
        String ciphertext = crypto.encrypt(pair.getPublic(), plaintext);
        byte[] wire = java.util.Base64.getDecoder().decode(ciphertext);
        wire[wire.length - 1] ^= 1;
        String tampered = java.util.Base64.getEncoder().encodeToString(wire);
        assertThrows(
                IllegalArgumentException.class,
                () -> crypto.decrypt(pair.getPrivate(), tampered)
        );
    }

    @Test
    void wrongPrivateKeyCannotDecrypt() throws Exception {
        KeyPair senderKey = keyPair();
        KeyPair otherKey = keyPair();
        String ciphertext = crypto.encrypt(
                senderKey.getPublic(),
                "secret".getBytes(StandardCharsets.UTF_8)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> crypto.decrypt(otherKey.getPrivate(), ciphertext)
        );
    }

    @Test
    void knownSha256Vector() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MeshHash.sha256Hex("abc")
        );
    }

    @Test
    void paymentHashIsStableForEquivalentAmounts() {
        java.util.UUID paymentId = java.util.UUID.randomUUID();
        String first = MeshHash.paymentHash(
                paymentId, "1234567890123456", "1234567890123457",
                new java.math.BigDecimal("500.00"), "INR", 1000L
        );
        String second = MeshHash.paymentHash(
                paymentId, "1234567890123456", "1234567890123457",
                new java.math.BigDecimal("500.0"), "INR", 1000L
        );
        assertEquals(first, second);
    }
}
