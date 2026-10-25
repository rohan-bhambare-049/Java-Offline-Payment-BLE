package com.pay.offline.controller;

import com.pay.offline.dto.CreatePaymentRequest;
import com.pay.offline.dto.PaymentInstructionResponse;
import com.pay.offline.service.PaymentInstructionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentInstructionService paymentInstructionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentInstructionResponse createPaymentInstruction(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        return paymentInstructionService.createInstruction(
                authentication.getName(),
                request,
                idempotencyKey
        );
    }

    @GetMapping("/{instructionUuid}")
    public PaymentInstructionResponse getInstruction(
            @PathVariable String instructionUuid,
            Authentication authentication
    ) {
        return paymentInstructionService.getInstructionByUuid(
                instructionUuid,
                authentication.getName()
        );
    }
}