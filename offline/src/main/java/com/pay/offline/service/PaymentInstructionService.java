package com.pay.offline.service;

import com.pay.offline.dto.CreatePaymentRequest;
import com.pay.offline.dto.PaymentInstructionResponse;
import com.pay.offline.entity.Account;
import com.pay.offline.entity.IdempotencyRecord;
import com.pay.offline.entity.PaymentInstruction;
import com.pay.offline.entity.User;
import com.pay.offline.repository.AccountRepository;
import com.pay.offline.repository.IdempotencyRecordRepository;
import com.pay.offline.repository.PaymentInstructionRepository;
import com.pay.offline.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentInstructionService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final PaymentInstructionRepository paymentInstructionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Transactional
    public PaymentInstructionResponse createInstruction(
            String authenticatedEmail,
            CreatePaymentRequest request,
            String idempotencyKey
    ) {
        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);

        validateIdempotencyKey(idempotencyKey);

        if (request == null
                || request.receiverAccountNumber() == null
                || request.amount() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Receiver account number and amount are required"
            );
        }

        String receiverNumber = request.receiverAccountNumber();

        if (!receiverNumber.matches("\\d{16}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Receiver account number must contain exactly 16 digits"
            );
        }

        if (request.amount().signum() <= 0
                || request.amount().scale() > 2
                || request.amount().precision()
                - request.amount().scale() > 17) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid payment amount"
            );
        }

        User sender = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Authenticated user was not found"
                ));

        if (sender.getStatus() != User.Status.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User account is not active"
            );
        }

        Account senderAccount = accountRepository.findByUserId(sender.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Sender account was not found"
                ));

        if (senderAccount.getStatus() != Account.Status.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Sender account is not active"
            );
        }

        Account receiverAccount = accountRepository
                .findByAccountNumber(receiverNumber)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Receiver account was not found"
                ));

        if (receiverAccount.getStatus() != Account.Status.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Receiver account is not active"
            );
        }

        if (senderAccount.getId().equals(receiverAccount.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "You cannot create a payment instruction for your own account"
            );
        }

        String fingerprint = createFingerprint(
                senderAccount.getId(),
                receiverAccount.getId(),
                request.amount()
        );

        var existingRecord = idempotencyRecordRepository
                .findByIdempotencyKey(idempotencyKey);

        if (existingRecord.isPresent()) {
            IdempotencyRecord record = existingRecord.get();

            if (!fingerprint.equals(record.getRequestFingerprint())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key was already used for a different payment"
                );
            }

            if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
                return PaymentInstructionResponse.from(
                        record.getPaymentInstruction()
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A request with this idempotency key is already being processed or failed"
            );
        }

        PaymentInstruction instruction = new PaymentInstruction(
                senderAccount,
                receiverAccount,
                request.amount(),
                LocalDateTime.now().plusHours(24)
        );

        PaymentInstruction savedInstruction =
                paymentInstructionRepository.saveAndFlush(instruction);

        IdempotencyRecord record = new IdempotencyRecord(
                idempotencyKey,
                fingerprint,
                savedInstruction
        );

        record.setStatus(IdempotencyRecord.Status.COMPLETED);
        record.setResponseCode(201);
        record.setCompletedAt(LocalDateTime.now());

        try {
            idempotencyRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException exception) {
            /*
             * The transaction must roll back if the unique idempotency key
             * constraint is violated. Do not catch this exception and
             * continue using the same transaction.
             */
            throw exception;
        }

        return PaymentInstructionResponse.from(savedInstruction);
    }

    @Transactional(readOnly = true)
    public PaymentInstructionResponse getInstructionByUuid(
            String instructionUuid,
            String authenticatedEmail
    ) {
        if (instructionUuid == null || instructionUuid.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment instruction UUID is required"
            );
        }

        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);

        PaymentInstruction instruction = paymentInstructionRepository
                .findByInstructionUuid(instructionUuid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Payment instruction not found"
                ));

        boolean isSender = instruction.getSenderAccount()
                .getUser().getEmail().equalsIgnoreCase(email);

        boolean isReceiver = instruction.getReceiverAccount()
                .getUser().getEmail().equalsIgnoreCase(email);

        if (!isSender && !isReceiver) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You are not authorized to view this payment instruction"
            );
        }

        return PaymentInstructionResponse.from(instruction);
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()
                || key.length() > 128
                || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A valid Idempotency-Key header is required"
            );
        }
    }

    private String createFingerprint(
            Long senderAccountId,
            Long receiverAccountId,
            java.math.BigDecimal amount
    ) {
        String normalizedAmount = amount.stripTrailingZeros().toPlainString();

        String input = senderAccountId + "|"
                + receiverAccountId + "|"
                + normalizedAmount + "|INR";

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }
}