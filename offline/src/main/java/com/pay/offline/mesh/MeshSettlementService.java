package com.pay.offline.mesh;

import com.pay.offline.entity.Account;
import com.pay.offline.entity.LedgerEntry;
import com.pay.offline.entity.PaymentInstruction;
import com.pay.offline.entity.Transaction;
import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;
import com.pay.offline.mesh.dto.MeshPaymentPayload;
import com.pay.offline.mesh.entity.MeshPacketReceipt;
import com.pay.offline.mesh.repository.MeshPacketReceiptRepository;
import com.pay.offline.repository.AccountRepository;
import com.pay.offline.repository.LedgerEntryRepository;
import com.pay.offline.repository.PaymentInstructionRepository;
import com.pay.offline.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The single database transaction that turns a verified mesh
 * payload into money movement, or into a recorded rejection.
 *
 * This service is separate from MeshIngestionService on purpose.
 * Ingestion does the cheap, non transactional checks first: hash,
 * claim, decrypt, payload hash, freshness. Only a packet that
 * passes all of those reaches this transaction. Keeping the
 * transaction short reduces lock time on the two accounts.
 *
 * The settlement rules mirror PaymentSettlementService, because
 * the same ledger rules must hold whether a payment arrived online
 * or through the mesh: both accounts must exist and be active,
 * currency must be INR, sender and receiver must differ, amount
 * must be positive with at most two decimal places, and the sender
 * must have enough balance. Account rows are locked in ascending
 * id order, the same deadlock precaution the online service uses.
 *
 * Idempotency inside this transaction:
 * - If the instruction UUID already exists and is settled, the
 *   existing transaction is returned and no money moves again.
 * - If it already exists and is rejected or expired, that outcome
 *   is returned again, so a replay is deterministic.
 * - If a receipt already exists for the packet hash, the packet is
 *   dropped as a duplicate.
 * The unique constraints on instruction UUID, transaction payment
 * instruction id, and packet hash are the last line of defence if
 * two transactions race past the checks above. In that case the
 * database refuses the second write, the transaction rolls back,
 * and the ingestion service maps the failure to DUPLICATE_DROPPED.
 */
@Service
@RequiredArgsConstructor
public class MeshSettlementService {

    private final AccountRepository accountRepository;
    private final PaymentInstructionRepository paymentInstructionRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MeshPacketReceiptRepository meshPacketReceiptRepository;

    @Transactional
    public MeshIngestionResult settle(
            MeshPacket packet,
            MeshPaymentPayload payload,
            String packetHash,
            String bridgeNodeId
    ) {
        String instructionUuid = payload.paymentId().toString();

        Optional<PaymentInstruction> existingInstruction =
                paymentInstructionRepository.findByInstructionUuid(instructionUuid);
        if (existingInstruction.isPresent()) {
            return resultForExistingInstruction(packetHash, existingInstruction.get());
        }

        Optional<MeshPacketReceipt> existingReceipt =
                meshPacketReceiptRepository.findByPacketHash(packetHash);
        if (existingReceipt.isPresent()) {
            return MeshIngestionResult.duplicate(
                    packetHash,
                    existingReceipt.get().getInstructionUuid(),
                    "Packet was already received by the server"
            );
        }

        Optional<Account> senderAccount =
                accountRepository.findByAccountNumber(payload.senderAccountNumber());
        Optional<Account> receiverAccount =
                accountRepository.findByAccountNumber(payload.receiverAccountNumber());
        if (senderAccount.isEmpty() || receiverAccount.isEmpty()) {
            return MeshIngestionResult.invalid(
                    packetHash,
                    "Sender or receiver account was not found"
            );
        }

        LocalDateTime createdAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(payload.createdAt()),
                ZoneId.systemDefault()
        );
        LocalDateTime expiresAt = createdAt.plusHours(24);
        LocalDateTime now = LocalDateTime.now();

        if (!expiresAt.isAfter(now)) {
            PaymentInstruction instruction = buildInstruction(
                    senderAccount.get(), receiverAccount.get(), payload, expiresAt
            );
            instruction.setStatus(PaymentInstruction.Status.EXPIRED);
            paymentInstructionRepository.saveAndFlush(instruction);
            saveReceipt(
                    packet, packetHash, bridgeNodeId,
                    MeshIngestionOutcome.EXPIRED, instructionUuid
            );
            return MeshIngestionResult.expired(
                    packetHash,
                    "Payment instruction has expired"
            );
        }

        String rejection = validateAccountsAndAmount(
                senderAccount.get(), receiverAccount.get(), payload.amount(), payload.currency()
        );
        if (rejection != null) {
            PaymentInstruction instruction = buildInstruction(
                    senderAccount.get(), receiverAccount.get(), payload, expiresAt
            );
            instruction.setStatus(PaymentInstruction.Status.REJECTED);
            paymentInstructionRepository.saveAndFlush(instruction);
            Transaction transaction = new Transaction(instruction);
            transaction.setStatus(Transaction.Status.FAILED);
            transaction.setFailureReason(rejection);
            transaction.setCompletedAt(now);
            transactionRepository.saveAndFlush(transaction);
            saveReceipt(
                    packet, packetHash, bridgeNodeId,
                    MeshIngestionOutcome.REJECTED, instructionUuid
            );
            return MeshIngestionResult.rejected(packetHash, instructionUuid, rejection);
        }

        Long senderId = senderAccount.get().getId();
        Long receiverId = receiverAccount.get().getId();
        List<Long> accountIds = List.of(senderId, receiverId).stream().sorted().toList();
        Account first = accountRepository.findByIdForUpdate(accountIds.get(0))
                .orElseThrow(() -> new IllegalStateException("Payment account no longer exists"));
        Account second = accountRepository.findByIdForUpdate(accountIds.get(1))
                .orElseThrow(() -> new IllegalStateException("Payment account no longer exists"));
        Account sender = first.getId().equals(senderId) ? first : second;
        Account receiver = first.getId().equals(receiverId) ? first : second;

        if (sender.getBalance().compareTo(payload.amount()) < 0) {
            PaymentInstruction instruction = buildInstruction(
                    sender, receiver, payload, expiresAt
            );
            instruction.setStatus(PaymentInstruction.Status.REJECTED);
            paymentInstructionRepository.saveAndFlush(instruction);
            Transaction transaction = new Transaction(instruction);
            transaction.setStatus(Transaction.Status.FAILED);
            transaction.setFailureReason("Insufficient funds");
            transaction.setCompletedAt(now);
            transactionRepository.saveAndFlush(transaction);
            saveReceipt(
                    packet, packetHash, bridgeNodeId,
                    MeshIngestionOutcome.REJECTED, instructionUuid
            );
            return MeshIngestionResult.rejected(
                    packetHash, instructionUuid, "Insufficient funds"
            );
        }

        PaymentInstruction instruction = buildInstruction(
                sender, receiver, payload, expiresAt
        );
        instruction.setStatus(PaymentInstruction.Status.PENDING_SYNC);
        instruction = paymentInstructionRepository.saveAndFlush(instruction);

        Transaction transaction = new Transaction(instruction);
        transaction.setStatus(Transaction.Status.PROCESSING);
        transaction = transactionRepository.saveAndFlush(transaction);

        sender.setBalance(sender.getBalance().subtract(payload.amount()));
        receiver.setBalance(receiver.getBalance().add(payload.amount()));
        accountRepository.save(sender);
        accountRepository.save(receiver);

        ledgerEntryRepository.save(
                new LedgerEntry(transaction, sender, LedgerEntry.EntryType.DEBIT, payload.amount())
        );
        ledgerEntryRepository.save(
                new LedgerEntry(transaction, receiver, LedgerEntry.EntryType.CREDIT, payload.amount())
        );

        transaction.setStatus(Transaction.Status.SUCCESS);
        transaction.setCompletedAt(now);
        instruction.setStatus(PaymentInstruction.Status.SETTLED);
        instruction.setSettledAt(now);

        transactionRepository.flush();
        accountRepository.flush();
        ledgerEntryRepository.flush();
        paymentInstructionRepository.flush();

        saveReceipt(
                packet, packetHash, bridgeNodeId,
                MeshIngestionOutcome.SETTLED, instructionUuid
        );

        return MeshIngestionResult.settled(
                packetHash,
                instructionUuid,
                transaction.getTransactionUuid()
        );
    }

    private PaymentInstruction buildInstruction(
            Account sender,
            Account receiver,
            MeshPaymentPayload payload,
            LocalDateTime expiresAt
    ) {
        PaymentInstruction instruction = new PaymentInstruction(
                sender, receiver, payload.amount(), expiresAt
        );
        instruction.setInstructionUuid(payload.paymentId().toString());
        instruction.setCurrency(payload.currency());
        return instruction;
    }

    private String validateAccountsAndAmount(
            Account sender,
            Account receiver,
            BigDecimal amount,
            String currency
    ) {
        if (sender.getStatus() != Account.Status.ACTIVE
                || receiver.getStatus() != Account.Status.ACTIVE) {
            return "Sender and receiver accounts must be active";
        }
        if (sender.getId().equals(receiver.getId())) {
            return "Sender and receiver accounts must be different";
        }
        if (!"INR".equals(currency)
                || !"INR".equals(sender.getCurrency())
                || !"INR".equals(receiver.getCurrency())) {
            return "Only INR payments are supported";
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            return "Payment amount is invalid";
        }
        return null;
    }

    private MeshIngestionResult resultForExistingInstruction(
            String packetHash,
            PaymentInstruction instruction
    ) {
        if (instruction.getStatus() == PaymentInstruction.Status.SETTLED) {
            Optional<Transaction> transaction = transactionRepository
                    .findByPaymentInstructionId(instruction.getId());
            if (transaction.isPresent()) {
                return MeshIngestionResult.settled(
                        packetHash,
                        instruction.getInstructionUuid(),
                        transaction.get().getTransactionUuid()
                );
            }
            return MeshIngestionResult.duplicate(
                    packetHash,
                    instruction.getInstructionUuid(),
                    "Payment was already settled"
            );
        }
        if (instruction.getStatus() == PaymentInstruction.Status.REJECTED) {
            return MeshIngestionResult.rejected(
                    packetHash,
                    instruction.getInstructionUuid(),
                    "Payment was already rejected"
            );
        }
        if (instruction.getStatus() == PaymentInstruction.Status.EXPIRED) {
            return MeshIngestionResult.expired(
                    packetHash,
                    "Payment instruction has expired"
            );
        }
        return MeshIngestionResult.duplicate(
                packetHash,
                instruction.getInstructionUuid(),
                "Payment instruction already exists"
        );
    }

    private void saveReceipt(
            MeshPacket packet,
            String packetHash,
            String bridgeNodeId,
            MeshIngestionOutcome outcome,
            String instructionUuid
    ) {
        meshPacketReceiptRepository.saveAndFlush(
                new MeshPacketReceipt(
                        packetHash,
                        packet.packetId(),
                        packet.originDeviceId(),
                        bridgeNodeId,
                        packet.hopCount(),
                        outcome,
                        instructionUuid
                )
        );
    }
}
