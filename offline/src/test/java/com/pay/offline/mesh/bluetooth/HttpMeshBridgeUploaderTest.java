package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpMeshBridgeUploaderTest {

    @Test
    void uploadPostsPacketAndParsesSettledResult() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v1/mesh/bridge/ingest", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = "{\"outcome\":\"SETTLED\",\"packetHash\":\"abc123\","
                    + "\"instructionUuid\":\"instr-1\",\"transactionUuid\":\"tx-1\","
                    + "\"message\":\"Payment settled successfully\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpMeshBridgeUploader uploader = new HttpMeshBridgeUploader(
                    "http://localhost:" + port, "phone-bridge", "user@example.com", "password123"
            );
            MeshPacket packet = new MeshPacket(
                    UUID.randomUUID(), "phone-origin", System.currentTimeMillis(), 5, 0, "ciphertext"
            );
            MeshIngestionResult result = uploader.upload(packet);
            assertEquals(MeshIngestionOutcome.SETTLED, result.outcome());
            assertEquals("instr-1", result.instructionUuid());
            assertEquals("tx-1", result.transactionUuid());
            assertEquals(true, receivedBody.get().contains("\"ciphertext\":\"ciphertext\""));
            assertEquals(true, receivedAuth.get() != null && receivedAuth.get().startsWith("Basic "));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void non200ResponseThrowsInsteadOfReportingSettled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v1/mesh/bridge/ingest", exchange -> {
            byte[] bytes = "unauthorized".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpMeshBridgeUploader uploader = new HttpMeshBridgeUploader(
                    "http://localhost:" + port, "phone-bridge", "user@example.com", "wrong"
            );
            MeshPacket packet = new MeshPacket(
                    UUID.randomUUID(), "phone-origin", System.currentTimeMillis(), 5, 0, "ciphertext"
            );
            assertThrows(IllegalStateException.class, () -> uploader.upload(packet));
        } finally {
            server.stop(0);
        }
    }
}
