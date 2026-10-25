package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Real HTTP bridge uploader for a phone with internet.
 *
 * Posts the packet JSON to POST /api/v1/mesh/bridge/ingest on
 * the backend, with HTTP Basic authentication for the user who
 * owns the bridge phone, and with the bridge node id header for
 * audit. The backend is the same Spring Boot backend in this
 * project, so the endpoint, the packet format, and the result
 * format are defined by this project, not by a third party.
 *
 * Production must use HTTPS. HTTP is used here only because
 * the base URL is supplied by the caller, and a local test
 * server runs over HTTP. The caller is responsible for passing
 * an HTTPS URL in production, and for never logging the
 * password or the Authorization header.
 */
public class HttpMeshBridgeUploader implements MeshBridgeUploader {

    private final String baseUrl;
    private final String bridgeNodeId;
    private final String username;
    private final String password;
    private final MeshPacketJsonCodec codec = new MeshPacketJsonCodec();

    public HttpMeshBridgeUploader(
            String baseUrl,
            String bridgeNodeId,
            String username,
            String password
    ) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.bridgeNodeId = bridgeNodeId;
        this.username = username;
        this.password = password;
    }

    @Override
    public MeshIngestionResult upload(MeshPacket packet) {
        try {
            URL url = new URL(baseUrl + "/api/v1/mesh/bridge/ingest");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Bridge-Node-Id", bridgeNodeId);
            String credentials = username + ":" + password;
            connection.setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                            credentials.getBytes(StandardCharsets.UTF_8)
                    )
            );
            byte[] body = codec.toJson(packet).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status == 200
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = stream == null
                    ? ""
                    : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (status != 200) {
                throw new IllegalStateException(
                        "Bridge upload failed with HTTP status " + status + ": " + response
                );
            }
            return parseResult(response);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Bridge upload failed", exception);
        }
    }

    private MeshIngestionResult parseResult(String json) {
        String outcome = readStringOrNull(json, "outcome");
        if (outcome == null) {
            throw new IllegalStateException("Bridge response has no outcome");
        }
        return new MeshIngestionResult(
                MeshIngestionOutcome.valueOf(outcome),
                readStringOrNull(json, "packetHash"),
                readStringOrNull(json, "instructionUuid"),
                readStringOrNull(json, "transactionUuid"),
                readStringOrNull(json, "message")
        );
    }

    private String readStringOrNull(String json, String field) {
        int keyIndex = json.indexOf("\"" + field + "\"");
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', keyIndex + field.length() + 2);
        if (colonIndex < 0) {
            return null;
        }
        int start = colonIndex + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (json.startsWith("null", start)) {
            return null;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                return value.toString();
            }
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    default -> value.append(next);
                }
            } else {
                value.append(ch);
            }
        }
        return value.toString();
    }
}
