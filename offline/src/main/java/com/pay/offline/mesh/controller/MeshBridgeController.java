package com.pay.offline.mesh.controller;

import com.pay.offline.mesh.MeshIngestionService;
import com.pay.offline.mesh.crypto.ServerKeyHolder;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The production bridge endpoint.
 *
 * This is the endpoint a real phone calls when it gets internet
 * again, after carrying a packet through the mesh. The request body
 * is a MeshPacket, exactly as it travelled over Bluetooth, with
 * the ciphertext unchanged.
 *
 * The bridge node id header is optional. If it is missing, the
 * authenticated username is recorded instead. The header is an
 * audit label only. It never decides whether a packet settles.
 * Authorisation comes from the existing HTTP Basic authentication
 * for the whole API, which SecurityConfig already enforces for
 * every endpoint except health and registration. No endpoint was
 * opened to anonymous users for this feature.
 *
 * The response is always HTTP 200 with an outcome in the body,
 * even for a duplicate or a rejection. That is deliberate, and it
 * follows the reference API: a duplicate is a normal mesh event,
 * not a client error, and the bridge phone needs the outcome
 * value to know whether to keep carrying the packet or drop it.
 * A malformed request body still returns HTTP 400 through
 * validation, and an unauthenticated request returns HTTP 401.
 */
@RestController
@RequestMapping("/api/v1/mesh")
@RequiredArgsConstructor
public class MeshBridgeController {

    private final MeshIngestionService meshIngestionService;
    private final ServerKeyHolder serverKeyHolder;

    @GetMapping("/server-public-key")
    public Map<String, String> serverPublicKey() {
        return Map.of(
                "algorithm", "RSA",
                "format", "X509",
                "publicKeyBase64", serverKeyHolder.getPublicKeyBase64()
        );
    }

    @PostMapping("/bridge/ingest")
    public MeshIngestionResult ingest(
            Authentication authentication,
            @RequestHeader(value = "X-Bridge-Node-Id", required = false) String bridgeNodeId,
            @Valid @RequestBody MeshPacket packet
    ) {
        String bridge = bridgeNodeId;
        if (bridge == null || bridge.isBlank()) {
            bridge = authentication != null ? authentication.getName() : "unknown-bridge";
        }
        return meshIngestionService.ingest(packet, bridge);
    }
}
