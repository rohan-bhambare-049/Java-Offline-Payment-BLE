package com.pay.offline.mesh.crypto;

import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAKey;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hybrid encryption, in the same shape as the reference project.
 *
 * RSA alone cannot encrypt a whole payment payload, because a 2048 bit
 * RSA key can only encrypt a small block. So each packet uses a fresh
 * AES 256 key, generated only for that packet:
 *
 * 1. The payment JSON is encrypted with AES 256 GCM. GCM is
 *    authenticated encryption, so flipping even one bit in the
 *    ciphertext makes decryption fail. That is the tamper check.
 * 2. Only the AES key is encrypted with the server RSA public key,
 *    using OAEP with SHA256.
 * 3. The wire bytes are: RSA encrypted AES key, then the 12 byte GCM
 *    IV, then the AES ciphertext plus its GCM tag. The whole byte
 *    string is Base64 encoded for JSON transport.
 *
 * Only the server private key can unwrap the AES key, so an
 * intermediate phone that carries the packet cannot read the amount,
 * the account numbers, or the payment id. It only sees opaque bytes.
 *
 * This class uses only the Java standard library. No new dependency
 * was added for cryptography.
 */
@Service
public class HybridCryptoService {

    private static final String RSA_TRANSFORMATION =
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    public String encrypt(PublicKey publicKey, byte[] plaintext) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(AES_KEY_BITS, secureRandom);
            SecretKey aesKey = keyGenerator.generateKey();

            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
            aesCipher.init(
                    Cipher.ENCRYPT_MODE,
                    aesKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            byte[] aesCiphertext = aesCipher.doFinal(plaintext);

            Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            byte[] wire = new byte[encryptedAesKey.length + iv.length + aesCiphertext.length];
            System.arraycopy(encryptedAesKey, 0, wire, 0, encryptedAesKey.length);
            System.arraycopy(iv, 0, wire, encryptedAesKey.length, iv.length);
            System.arraycopy(
                    aesCiphertext, 0, wire,
                    encryptedAesKey.length + iv.length, aesCiphertext.length
            );
            return Base64.getEncoder().encodeToString(wire);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Packet encryption failed", exception);
        }
    }

    /**
     * Throws IllegalArgumentException for a malformed or tampered
     * packet. Callers map that to an INVALID ingestion outcome. The
     * exception message is deliberately generic: it must not tell an
     * attacker which part of the packet failed.
     */
    public byte[] decrypt(PrivateKey privateKey, String ciphertextBase64) {
        try {
            byte[] wire = Base64.getDecoder().decode(ciphertextBase64);
            int rsaBytes = rsaKeyBytes(privateKey);
            if (wire.length <= rsaBytes + GCM_IV_BYTES) {
                throw new IllegalArgumentException("Ciphertext is too short");
            }

            byte[] encryptedAesKey = new byte[rsaBytes];
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] aesCiphertext = new byte[wire.length - rsaBytes - GCM_IV_BYTES];
            System.arraycopy(wire, 0, encryptedAesKey, 0, rsaBytes);
            System.arraycopy(wire, rsaBytes, iv, 0, GCM_IV_BYTES);
            System.arraycopy(wire, rsaBytes + GCM_IV_BYTES, aesCiphertext, 0, aesCiphertext.length);

            Cipher rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION);
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);

            Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
            aesCipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKeyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            return aesCipher.doFinal(aesCiphertext);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalArgumentException("Ciphertext could not be decrypted", exception);
        }
    }

    private int rsaKeyBytes(Key key) {
        if (key instanceof RSAKey rsaKey) {
            return (rsaKey.getModulus().bitLength() + 7) / 8;
        }
        throw new IllegalArgumentException("Server key is not an RSA key");
    }
}
