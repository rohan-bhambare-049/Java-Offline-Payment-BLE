package com.pay.offline.mesh;

import com.pay.offline.mesh.crypto.HybridCryptoService;
import com.pay.offline.mesh.crypto.ServerKeyHolder;
import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;
import com.pay.offline.mesh.repository.MeshPacketReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeshIngestionServiceTest {

    @Mock
    private MeshPacketReceiptRepository receiptRepository;

    @Mock
    private MeshSettlementService settlementService;

    private ServerKeyHolder serverKeyHolder;
    private HybridCryptoService crypto;
    private MeshPacketFactory factory;
    private MeshIngestionService ingestion;

    @BeforeEach
    void setUp() {
        serverKeyHolder = new ServerKeyHolder(null, null);
        crypto = new HybridCryptoService();
        factory = new MeshPacketFactory(
                serverKeyHolder, crypto, new MeshPayloadCodec(), 5
        );
        ingestion = new MeshIngestionService(
                serverKeyHolder,
                crypto,
                new MeshPayloadCodec(),
                new MeshPacketClaimService(),
                receiptRepository,
                settlementService,
                24,
                5
        );
        lenient().when(receiptRepository.findByPacketHash(anyString()))
                .thenReturn(Optional.empty());
    }

    private MeshPacket validPacket() {
        return factory.createPacket(
                "sender-phone",
                "1234567890123456",
                "1234567890123457",
                new BigDecimal("500.00"),
                "INR"
        );
    }

    @Test
    void validPacketReachesSettlementOnceAndDuplicateIsDropped() {
        MeshPacket packet = validPacket();
        when(settlementService.settle(any(), any(), anyString(), anyString()))
                .thenReturn(MeshIngestionResult.settled("hash", "instruction", "transaction"));

        MeshIngestionResult first = ingestion.ingest(packet, "bridge-phone");
        MeshIngestionResult second = ingestion.ingest(packet, "bridge-phone");

        assertEquals(MeshIngestionOutcome.SETTLED, first.outcome());
        assertEquals(MeshIngestionOutcome.DUPLICATE_DROPPED, second.outcome());
        verify(settlementService, times(1)).settle(any(), any(), anyString(), anyString());
    }

    @Test
    void tamperedPacketIsInvalidAndNeverReachesSettlement() {
        MeshPacket packet = validPacket();
        byte[] wire = Base64.getDecoder().decode(packet.ciphertext());
        wire[wire.length - 1] ^= 1;
        MeshPacket tampered = new MeshPacket(
                packet.packetId(),
                packet.originDeviceId(),
                packet.createdAt(),
                packet.ttl(),
                packet.hopCount(),
                Base64.getEncoder().encodeToString(wire)
        );

        MeshIngestionResult result = ingestion.ingest(tampered, "bridge-phone");

        assertEquals(MeshIngestionOutcome.INVALID, result.outcome());
        verify(settlementService, never()).settle(any(), any(), anyString(), anyString());
    }

    @Test
    void oldPacketIsExpiredAndNeverReachesSettlement() {
        MeshPacket packet = factory.createPacketWithDetails(
                "sender-phone",
                java.util.UUID.randomUUID(),
                "1234567890123456",
                "1234567890123457",
                new BigDecimal("100.00"),
                "INR",
                System.currentTimeMillis() - 25L * 3_600_000L
        );

        MeshIngestionResult result = ingestion.ingest(packet, "bridge-phone");

        assertEquals(MeshIngestionOutcome.EXPIRED, result.outcome());
        verify(settlementService, never()).settle(any(), any(), anyString(), anyString());
    }
}
