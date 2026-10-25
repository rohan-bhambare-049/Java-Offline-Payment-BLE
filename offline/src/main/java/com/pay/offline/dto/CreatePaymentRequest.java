package com.pay.offline.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePaymentRequest(

        @NotBlank(message = "Receiver account number is required")
        @Size(min = 16, max = 16,
                message = "Receiver account number must contain 16 digits")
        String receiverAccountNumber,

        @NotNull(message = "Payment amount is required")
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Payment amount must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "Payment amount can have at most 17 integer digits and 2 decimal places")
        BigDecimal amount

) {
}