package com.pay.offline.service;

import com.pay.offline.dto.PaymentSettlementResponse;
import com.pay.offline.entity.Account;
import com.pay.offline.entity.LedgerEntry;
import com.pay.offline.entity.PaymentInstruction;
import com.pay.offline.entity.Transaction;
import com.pay.offline.repository.AccountRepository;
import com.pay.offline.repository.LedgerEntryRepository;
import com.pay.offline.repository.PaymentInstructionRepository;
import com.pay.offline.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PaymentSettlementService {
    
    private final AccountRepository accountRepository;
    private final PaymentInstructionRepository paymentInstructionRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public PaymentSettlementResponse settlePayment(
            String instructionUuid,
            String authenticatedEmail
    ) {

        if (instructionUuid == null || instructionUuid.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment instruction UUID is required"
            );
        }

        PaymentInstruction instruction = paymentInstructionRepository
                .findByInstructionUuidForUpdate(instructionUuid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Payment instruction not found"
                ));

        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);

        boolean isSender = instruction.getSenderAccount()
                .getUser()
                .getEmail()
                .equalsIgnoreCase(email);

        if (!isSender) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only the sender can initiate settlement"
            );
        }

        /*
         * A previously settled payment must never be settled again.
         */
        if (instruction.getStatus() == PaymentInstruction.Status.SETTLED) {

            Transaction existing = transactionRepository
                    .findByPaymentInstructionId(instruction.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Settled instruction has no transaction record"
                    ));

            return toResponse(
                    instruction,
                    existing,
                    "Payment has already been settled"
            );
        }

        /*
         * An instruction already marked EXPIRED must not be settled.
         * Its status is already persisted, so we can reject it directly.
         */
        if (instruction.getStatus() == PaymentInstruction.Status.EXPIRED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment instruction has expired"
            );
        }

        if (instruction.getStatus() == PaymentInstruction.Status.REJECTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment instruction has already been rejected"
            );
        }

        if (instruction.getStatus() != PaymentInstruction.Status.CREATED
                && instruction.getStatus()
                != PaymentInstruction.Status.PENDING_SYNC) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment instruction cannot be settled from its current state"
            );
        }

        LocalDateTime now = LocalDateTime.now();

        /*
         * IMPORTANT:
         *
         * Do not throw an exception immediately after setting EXPIRED.
         *
         * Returning normally allows this @Transactional method to commit
         * the status update. The controller will translate this result
         * into HTTP 409 after the transaction has completed.
         */
        if (!instruction.getExpiresAt().isAfter(now)) {

            instruction.setStatus(PaymentInstruction.Status.EXPIRED);

            return toExpiredResponse(instruction);
        }

        Long senderId = instruction.getSenderAccount().getId();
        Long receiverId = instruction.getReceiverAccount().getId();

        /*
         * Always acquire account locks in ascending ID order.
         * This reduces deadlock risk when concurrent payments share accounts.
         */
        List<Long> accountIds = List.of(senderId, receiverId)
                .stream()
                .sorted()
                .toList();

        Account first = accountRepository
                .findByIdForUpdate(accountIds.get(0))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Payment account no longer exists"
                ));

        Account second = accountRepository
                .findByIdForUpdate(accountIds.get(1))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Payment account no longer exists"
                ));

        Account sender = first.getId().equals(senderId)
                ? first
                : second;

        Account receiver = first.getId().equals(receiverId)
                ? first
                : second;

        if (sender.getStatus() != Account.Status.ACTIVE
                || receiver.getStatus() != Account.Status.ACTIVE) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Sender and receiver accounts must be active"
            );
        }

        if (sender.getId().equals(receiver.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Sender and receiver accounts must be different"
            );
        }

        if (!"INR".equals(sender.getCurrency())
                || !"INR".equals(receiver.getCurrency())
                || !"INR".equals(instruction.getCurrency())) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only INR payments are supported"
            );
        }

        BigDecimal amount = instruction.getAmount();

        if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment amount is invalid"
            );
        }

        if (sender.getBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Insufficient funds"
            );
        }

        /*
         * Create the transaction record first.
         * The unique constraint on payment_instruction_id protects against
         * duplicate transaction records.
         */
        Transaction transaction = new Transaction(instruction);

        transaction.setStatus(Transaction.Status.PROCESSING);

        transaction = transactionRepository.saveAndFlush(transaction);

        /*
         * Update balances only after validation and account locking.
         * These changes are committed atomically with the ledger entries.
         */
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        accountRepository.save(sender);
        accountRepository.save(receiver);

        LedgerEntry debit = new LedgerEntry(
                transaction,
                sender,
                LedgerEntry.EntryType.DEBIT,
                amount
        );

        LedgerEntry credit = new LedgerEntry(
                transaction,
                receiver,
                LedgerEntry.EntryType.CREDIT,
                amount
        );

        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);

        transaction.setStatus(Transaction.Status.SUCCESS);
        transaction.setCompletedAt(now);

        instruction.setStatus(PaymentInstruction.Status.SETTLED);
        instruction.setSettledAt(now);

        /*
         * Flush pending database changes before returning so constraint
         * violations are detected inside this transaction.
         */
        transactionRepository.flush();
        accountRepository.flush();
        ledgerEntryRepository.flush();
        paymentInstructionRepository.flush();

        return toResponse(
                instruction,
                transaction,
                "Payment settled successfully"
        );
    }

    /*
     * Produces an internal service result indicating that the instruction
     * expired. There is deliberately no transaction record or transaction
     * status for this response.
     *
     * The service method returns normally, allowing Spring to commit the
     * EXPIRED instruction status before the controller returns HTTP 409.
     */
    private PaymentSettlementResponse toExpiredResponse(
            PaymentInstruction instruction
    ) {

        return new PaymentSettlementResponse(
                instruction.getInstructionUuid(),
                null,
                PaymentInstruction.Status.EXPIRED.name(),
                null,
                instruction.getAmount(),
                instruction.getCurrency(),
                "Payment instruction has expired",
                null
        );
    }

    private PaymentSettlementResponse toResponse(
            PaymentInstruction instruction,
            Transaction transaction,
            String message
    ) {

        return new PaymentSettlementResponse(
                instruction.getInstructionUuid(),
                transaction.getTransactionUuid(),
                instruction.getStatus().name(),
                transaction.getStatus().name(),
                instruction.getAmount(),
                instruction.getCurrency(),
                message,
                transaction.getCompletedAt()
        );
    }

}
