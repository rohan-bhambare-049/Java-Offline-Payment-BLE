package com.pay.offline.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentSettlementResponse(
        String instructionUuid,
        String transactionUuid,
        String instructionStatus,
        String transactionStatus,
        BigDecimal amount,
        String currency,
        String message,
        LocalDateTime completedAt
) {
}