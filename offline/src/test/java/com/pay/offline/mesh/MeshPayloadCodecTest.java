package com.pay.offline.mesh;

import com.pay.offline.mesh.dto.MeshPaymentPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeshPayloadCodecTest {

    private final MeshPayloadCodec codec = new MeshPayloadCodec();

    private MeshPaymentPayload payload() {
        return new MeshPaymentPayload(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "1234567890123456",
                "1234567890123457",
                new BigDecimal("500.00"),
                "INR",
                1730000000000L,
                "abcdef0123456789"
        );
    }

    @Test
    void jsonRoundTripPreservesEveryField() {
        MeshPaymentPayload original = payload();
        MeshPaymentPayload parsed = codec.fromJson(codec.toJson(original));
        assertEquals(original, parsed);
    }

    @Test
    void quotedAmountIsAccepted() {
        String json = "{\"paymentId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"senderAccountNumber\":\"1234567890123456\","
                + "\"receiverAccountNumber\":\"1234567890123457\","
                + "\"amount\":\"500.00\",\"currency\":\"INR\","
                + "\"createdAt\":1730000000000,\"payloadHash\":\"abcdef0123456789\"}";
        MeshPaymentPayload parsed = codec.fromJson(json);
        assertEquals(new BigDecimal("500.00"), parsed.amount());
    }

    @Test
    void missingFieldIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.fromJson("{\"paymentId\":\"123e4567-e89b-12d3-a456-426614174000\"}")
        );
    }
}
