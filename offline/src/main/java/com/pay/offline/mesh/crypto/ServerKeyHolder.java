package com.pay.offline.mesh.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the server RSA key pair used by the mesh system.
 *
 * Two modes, in order of preference:
 *
 * 1. Persistent keys. Set mesh.crypto.publicKeyBase64 and
 *    mesh.crypto.privateKeyBase64 in application.properties. The
 *    values are Base64 of the X509 public key and the PKCS8 private
 *    key. Run MeshKeyGenerator once to create a pair, then paste the
 *    two values into the properties file. With persistent keys, a
 *    server restart does not invalidate packets that are still
 *    travelling through the mesh.
 *
 * 2. Ephemeral keys. If no keys are configured, a fresh RSA 2048 pair
 *    is generated at startup and a warning is logged. This is fine
 *    for a demo, but packets encrypted for the old key cannot be
 *    decrypted after a restart, because the private key is gone.
 *    That is the same limitation the reference demo has, and it is
 *    stated plainly here instead of being hidden.
 *
 * The private key is never exposed through any endpoint. Only the
 * public key is published, at GET /api/v1/mesh/server-public-key,
 * so a phone can encrypt a packet for the server.
 *
 * A phone must never receive the private key, and the private key
 * must never be committed to source control.
 */
@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public ServerKeyHolder(
            @Value("${mesh.crypto.publicKeyBase64:}") String publicKeyBase64,
            @Value("${mesh.crypto.privateKeyBase64:}") String privateKeyBase64
    ) {
        try {
            if (privateKeyBase64 != null && !privateKeyBase64.isBlank()) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                byte[] privateBytes = Base64.getDecoder().decode(privateKeyBase64.trim());
                this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                if (publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
                    byte[] publicBytes = Base64.getDecoder().decode(publicKeyBase64.trim());
                    this.publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
                } else {
                    this.publicKey = derivePublicKey(keyFactory);
                }
            } else if (publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
                throw new IllegalStateException(
                        "A mesh public key was configured without a private key. "
                                + "The server cannot decrypt packets without the private key."
                );
            } else {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                this.privateKey = pair.getPrivate();
                this.publicKey = pair.getPublic();
                log.warn(
                        "No persistent mesh RSA key is configured, so an ephemeral key pair "
                                + "was generated for this run only. Packets still in the mesh "
                                + "will not decrypt after a restart. Configure "
                                + "mesh.crypto.publicKeyBase64 and mesh.crypto.privateKeyBase64 "
                                + "for a persistent key pair."
                );
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Mesh RSA key could not be loaded", exception);
        }
    }

    private PublicKey derivePublicKey(KeyFactory keyFactory) throws Exception {
        if (privateKey instanceof RSAPrivateCrtKey crtKey) {
            return keyFactory.generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent())
            );
        }
        throw new IllegalStateException("Public key cannot be derived from this private key");
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
