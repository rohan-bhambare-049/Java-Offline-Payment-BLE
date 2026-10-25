package com.pay.offline.dto;

import com.pay.offline.entity.PaymentInstruction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentInstructionResponse(
        String instructionUuid,
        String senderAccountNumber,
        String receiverAccountNumber,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {

    public static PaymentInstructionResponse from(
            PaymentInstruction instruction
    ) {
        return new PaymentInstructionResponse(
                instruction.getInstructionUuid(),
                instruction.getSenderAccount().getAccountNumber(),
                instruction.getReceiverAccount().getAccountNumber(),
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getStatus().name(),
                instruction.getCreatedAt(),
                instruction.getExpiresAt()
        );
    }
}