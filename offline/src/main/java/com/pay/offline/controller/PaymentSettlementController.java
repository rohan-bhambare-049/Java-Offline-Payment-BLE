package com.pay.offline.controller;

import com.pay.offline.dto.PaymentSettlementResponse;
import com.pay.offline.entity.PaymentInstruction;
import com.pay.offline.service.PaymentSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentSettlementController {

    private final PaymentSettlementService paymentSettlementService;

    @PostMapping("/{instructionUuid}/settle")
    @ResponseStatus(HttpStatus.OK)
    public PaymentSettlementResponse settlePayment(
            @PathVariable String instructionUuid,
            Authentication authentication
    ) {

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required"
            );
        }

        PaymentSettlementResponse response =
                paymentSettlementService.settlePayment(
                        instructionUuid,
                        authentication.getName()
                );

        /*
         * The service has returned, so its transaction has completed.
         * If the instruction has just expired, report HTTP 409 now.
         *
         * The EXPIRED status has already been committed by the service.
         */
        if (PaymentInstruction.Status.EXPIRED.name()
                .equals(response.instructionStatus())
                && response.transactionUuid() == null) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    response.message()
            );
        }

        return response;

    }

}