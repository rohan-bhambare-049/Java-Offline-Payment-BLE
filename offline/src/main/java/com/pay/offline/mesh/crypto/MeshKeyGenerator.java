package com.pay.offline.mesh.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * One time helper to create a persistent server key pair.
 *
 * Run it after the project compiles, from the offline folder:
 *
 *   mvn compile
 *   java -cp target/classes com.pay.offline.mesh.crypto.MeshKeyGenerator
 *
 * Copy the two printed values into application.properties
 * as mesh.crypto.publicKeyBase64 and mesh.crypto.privateKeyBase64.
 *
 * Treat the private key value like a password. Do not paste it into
 * chat, do not commit it, and do not put it in the Android app.
 */
public final class MeshKeyGenerator {

    private MeshKeyGenerator() {
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        System.out.println("MESH PUBLIC KEY BASE64");
        System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        System.out.println("MESH PRIVATE KEY BASE64");
        System.out.println(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    }
}
