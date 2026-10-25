package com.pay.offline.service;

import com.pay.offline.dto.PaymentSettlementResponse;
import com.pay.offline.entity.Account;
import com.pay.offline.entity.LedgerEntry;
import com.pay.offline.entity.PaymentInstruction;
import com.pay.offline.entity.Transaction;
import com.pay.offline.entity.User;
import com.pay.offline.repository.AccountRepository;
import com.pay.offline.repository.LedgerEntryRepository;
import com.pay.offline.repository.PaymentInstructionRepository;
import com.pay.offline.repository.TransactionRepository;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    private static final String INSTRUCTION_UUID =
            "123e4567-e89b-12d3-a456-426614174000";

    private static final String EXISTING_TRANSACTION_UUID =
            "223e4567-e89b-12d3-a456-426614174000";

    private static final String SENDER_EMAIL =
            "sender@example.com";

    private static final String OTHER_EMAIL =
            "other@example.com";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PaymentInstructionRepository paymentInstructionRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private PaymentSettlementService paymentSettlementService;

    private Account sender;

    private Account receiver;

    private PaymentInstruction instruction;

    private User senderUser;


    // ---------------------------------------------------------
    // TEST INITIALIZATION
    // ---------------------------------------------------------

    @BeforeEach
    void setUp() {
        sender = mock(Account.class);
        receiver = mock(Account.class);
        instruction = mock(PaymentInstruction.class);
        senderUser = mock(User.class);
    }


    // ---------------------------------------------------------
    // TEST 1: BLANK INSTRUCTION UUID
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectBlankInstructionUuid() {

        ResponseStatusException exception = expectStatus(
                HttpStatus.BAD_REQUEST,
                () -> paymentSettlementService.settlePayment(
                        " ",
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verifyNoInteractions(
                paymentInstructionRepository,
                accountRepository,
                transactionRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 2: INSTRUCTION DOES NOT EXIST
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectMissingInstruction() {

        when(
                paymentInstructionRepository
                        .findByInstructionUuidForUpdate(INSTRUCTION_UUID)
        ).thenReturn(Optional.empty());

        ResponseStatusException exception = expectStatus(
                HttpStatus.NOT_FOUND,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(paymentInstructionRepository)
                .findByInstructionUuidForUpdate(INSTRUCTION_UUID);

        verifyNoInteractions(
                accountRepository,
                transactionRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 3: UNAUTHORIZED SENDER
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectUnauthorizedSender() {

        stubOwnership();

        ResponseStatusException exception = expectStatus(
                HttpStatus.FORBIDDEN,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        OTHER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(accountRepository, never())
                .findByIdForUpdate(any());

        verifyNoInteractions(
                transactionRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 4: INSTRUCTION ALREADY EXPIRED
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectAlreadyExpiredInstruction() {

        stubOwnership();

        when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.EXPIRED);

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(accountRepository, never())
                .findByIdForUpdate(any());

        verifyNoInteractions(
                transactionRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 5: INSTRUCTION EXPIRES BEFORE SETTLEMENT
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldMarkInstructionExpiredWhenExpiryTimeHasPassed() {

        stubOwnership();

        when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.CREATED);

        when(instruction.getExpiresAt())
                .thenReturn(LocalDateTime.now().minusMinutes(1));

        PaymentSettlementResponse response =
                paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                );

        assertNotNull(response);

        assertEquals(
                "EXPIRED",
                response.instructionStatus()
        );

        assertEquals(
                "Payment instruction has expired",
                response.message()
        );

        assertNull(
                response.transactionUuid(),
                "An expired payment must not have a transaction UUID"
        );

        verify(instruction)
                .setStatus(PaymentInstruction.Status.EXPIRED);

        verify(accountRepository, never())
                .findByIdForUpdate(any());

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);

    }



    // ---------------------------------------------------------
    // TEST 6: REJECTED INSTRUCTION
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectRejectedInstruction() {

        stubOwnership();

        when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.REJECTED);

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(accountRepository, never())
                .findByIdForUpdate(any());

        verifyNoInteractions(
                transactionRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 7: UNSUPPORTED INSTRUCTION STATE
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectUnsupportedInstructionState() {

        stubOwnership();

        when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.PROCESSING);

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(accountRepository, never())
                .findByIdForUpdate(any());

        verifyNoInteractions(
                transactionRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 8: SENDER ACCOUNT DOES NOT EXIST
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectMissingSenderAccount() {

        stubValidMockSettlement("100.00", "1000.00");

        when(accountRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 9: RECEIVER ACCOUNT DOES NOT EXIST
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectMissingReceiverAccount() {

        stubValidMockSettlement("100.00", "1000.00");

        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 10: SENDER ACCOUNT IS INACTIVE
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectInactiveSenderAccount() {

        stubValidMockSettlement("100.00", "1000.00");

        when(sender.getStatus())
                .thenReturn(Account.Status.FROZEN);

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 11: RECEIVER ACCOUNT IS INACTIVE
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectInactiveReceiverAccount() {

        stubValidMockSettlement("100.00", "1000.00");

        when(receiver.getStatus())
                .thenReturn(Account.Status.CLOSED);

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 12: SENDER AND RECEIVER ARE THE SAME ACCOUNT
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectSameSenderAndReceiverAccount() {

        stubValidMockSettlement("100.00", "1000.00");

        /*
         * Simulate the same account ID being used as both
         * the sender and receiver.
         */
        when(receiver.getId())
                .thenReturn(1L);

        ResponseStatusException exception = expectStatus(
                HttpStatus.BAD_REQUEST,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 13: SENDER CURRENCY IS NOT INR
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectNonInrSenderCurrency() {

        stubValidMockSettlement("100.00", "1000.00");

        when(sender.getCurrency())
                .thenReturn("USD");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 14: RECEIVER CURRENCY IS NOT INR
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectNonInrReceiverCurrency() {

        stubValidMockSettlement("100.00", "1000.00");

        when(receiver.getCurrency())
                .thenReturn("USD");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 15: PAYMENT INSTRUCTION CURRENCY IS NOT INR
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectNonInrInstructionCurrency() {

        stubValidMockSettlement("100.00", "1000.00");

        when(instruction.getCurrency())
                .thenReturn("USD");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 16: PAYMENT AMOUNT IS MISSING
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectMissingPaymentAmount() {

        stubValidMockSettlement(null, "1000.00");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 17: PAYMENT AMOUNT IS ZERO
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectZeroPaymentAmount() {

        stubValidMockSettlement("0.00", "1000.00");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 18: PAYMENT AMOUNT HAS MORE THAN TWO DECIMAL PLACES
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectAmountWithMoreThanTwoDecimalPlaces() {

        stubValidMockSettlement("100.001", "1000.00");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 19: INSUFFICIENT FUNDS
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectInsufficientFunds() {

        stubValidMockSettlement("100.00", "50.00");

        ResponseStatusException exception = expectStatus(
                HttpStatus.CONFLICT,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        assertNotNull(exception);

        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));

        verifyNoInteractions(ledgerEntryRepository);
    }


    // ---------------------------------------------------------
    // TEST 20: SETTLED INSTRUCTION HAS NO TRANSACTION
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRejectSettledInstructionWithoutTransaction() {

        stubOwnership();

        when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.SETTLED);

        when(instruction.getId())
                .thenReturn(10L);

        when(
                transactionRepository.findByPaymentInstructionId(10L)
        ).thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                )
        );

        verify(transactionRepository)
                .findByPaymentInstructionId(10L);

        verifyNoInteractions(
                accountRepository,
                ledgerEntryRepository
        );
    }


    // ---------------------------------------------------------
    // TEST 21: ALREADY SETTLED PAYMENT IS IDEMPOTENT
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldReturnExistingTransactionWhenAlreadySettled() {

        stubOwnership();

        when(instruction.getId())
                .thenReturn(10L);

        when(instruction.getInstructionUuid())
                .thenReturn(INSTRUCTION_UUID);

        when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.SETTLED);

        when(instruction.getAmount())
                .thenReturn(new BigDecimal("100.00"));

        when(instruction.getCurrency())
                .thenReturn("INR");

        Transaction existingTransaction = mock(Transaction.class);

        when(
                transactionRepository.findByPaymentInstructionId(10L)
        ).thenReturn(Optional.of(existingTransaction));

        when(existingTransaction.getTransactionUuid())
                .thenReturn(EXISTING_TRANSACTION_UUID);

        when(existingTransaction.getStatus())
                .thenReturn(Transaction.Status.SUCCESS);

        LocalDateTime completedAt = LocalDateTime.now();

        when(existingTransaction.getCompletedAt())
                .thenReturn(completedAt);

        PaymentSettlementResponse response =
                paymentSettlementService.settlePayment(
                        INSTRUCTION_UUID,
                        SENDER_EMAIL
                );

        assertNotNull(response);

        assertEquals(
                INSTRUCTION_UUID,
                response.instructionUuid()
        );

        assertEquals(
                EXISTING_TRANSACTION_UUID,
                response.transactionUuid()
        );

        assertEquals(
                "SETTLED",
                response.instructionStatus()
        );

        assertEquals(
                "SUCCESS",
                response.transactionStatus()
        );

        assertMoneyEquals("100.00", response.amount());

        assertEquals("INR", response.currency());

        assertEquals(
                "Payment has already been settled",
                response.message()
        );

        assertEquals(
                completedAt,
                response.completedAt()
        );

        verify(transactionRepository)
                .findByPaymentInstructionId(10L);

        /*
         * Most importantly, another settlement must not
         * debit or credit an account again.
         */
        verifyNoInteractions(
                accountRepository,
                ledgerEntryRepository
        );

        /*
         * No new transaction should be created for a
         * previously settled instruction.
         */
        verify(transactionRepository, never())
                .saveAndFlush(any(Transaction.class));
    }


    // ---------------------------------------------------------
    // TEST 22: SUCCESSFUL SETTLEMENT FROM CREATED
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldSuccessfullySettleCreatedInstruction() {

        SettlementResult result = executeSuccessfulSettlement(
                PaymentInstruction.Status.CREATED
        );

        assertSuccessfulSettlement(result);
    }


    // ---------------------------------------------------------
    // TEST 23: SUCCESSFUL SETTLEMENT FROM PENDING_SYNC
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldSuccessfullySettlePendingSyncInstruction() {

        SettlementResult result = executeSuccessfulSettlement(
                PaymentInstruction.Status.PENDING_SYNC
        );

        assertSuccessfulSettlement(result);
    }


    // ---------------------------------------------------------
    // SHARED ASSERTIONS FOR SUCCESSFUL SETTLEMENT
    // ---------------------------------------------------------

    private void assertSuccessfulSettlement(SettlementResult result) {

        Account realSender = result.sender();

        Account realReceiver = result.receiver();

        PaymentInstruction realInstruction = result.instruction();

        PaymentSettlementResponse response = result.response();

        Transaction transaction = result.transaction();

        List<LedgerEntry> entries = result.ledgerEntries();


        // Verify actual account balance changes.

        assertMoneyEquals(
                "800.00",
                realSender.getBalance()
        );

        assertMoneyEquals(
                "700.00",
                realReceiver.getBalance()
        );


        // Verify the payment instruction.

        assertEquals(
                PaymentInstruction.Status.SETTLED,
                realInstruction.getStatus()
        );

        assertNotNull(
                realInstruction.getSettledAt()
        );


        // Verify the API response.

        assertEquals(
                realInstruction.getInstructionUuid(),
                response.instructionUuid()
        );

        assertEquals(
                transaction.getTransactionUuid(),
                response.transactionUuid()
        );

        assertEquals(
                "SETTLED",
                response.instructionStatus()
        );

        assertEquals(
                "SUCCESS",
                response.transactionStatus()
        );

        assertMoneyEquals(
                "200.00",
                response.amount()
        );

        assertEquals(
                "INR",
                response.currency()
        );

        assertEquals(
                "Payment settled successfully",
                response.message()
        );

        assertNotNull(
                response.completedAt()
        );


        // Verify the actual transaction state.

        assertNotNull(transaction.getTransactionUuid());

        assertEquals(
                Transaction.Status.SUCCESS,
                transaction.getStatus()
        );

        assertNotNull(
                transaction.getCompletedAt()
        );

        assertEquals(
                realInstruction.getSettledAt(),
                transaction.getCompletedAt()
        );


        // Exactly two ledger entries must be created.

        assertEquals(
                2,
                entries.size()
        );

        LedgerEntry debit = entries.stream()
                .filter(entry ->
                        entry.getEntryType()
                                == LedgerEntry.EntryType.DEBIT
                )
                .findFirst()
                .orElseThrow();

        LedgerEntry credit = entries.stream()
                .filter(entry ->
                        entry.getEntryType()
                                == LedgerEntry.EntryType.CREDIT
                )
                .findFirst()
                .orElseThrow();


        // Verify the debit entry.

        assertEquals(
                realSender,
                debit.getAccount()
        );

        assertEquals(
                transaction,
                debit.getTransaction()
        );

        assertMoneyEquals(
                "200.00",
                debit.getAmount()
        );

        assertEquals(
                "INR",
                debit.getCurrency()
        );


        // Verify the credit entry.

        assertEquals(
                realReceiver,
                credit.getAccount()
        );

        assertEquals(
                transaction,
                credit.getTransaction()
        );

        assertMoneyEquals(
                "200.00",
                credit.getAmount()
        );

        assertEquals(
                "INR",
                credit.getCurrency()
        );


        // Verify persistence operations were requested.

        verify(transactionRepository)
                .saveAndFlush(transaction);

        verify(accountRepository).save(realSender);

        verify(accountRepository).save(realReceiver);

        verify(ledgerEntryRepository, times(2))
                .save(any(LedgerEntry.class));

        verify(transactionRepository).flush();

        verify(accountRepository).flush();

        verify(ledgerEntryRepository).flush();

        verify(paymentInstructionRepository).flush();
    }


    // ---------------------------------------------------------
    // SHARED SETUP FOR REAL SUCCESSFUL SETTLEMENT TESTS
    // ---------------------------------------------------------

    private SettlementResult executeSuccessfulSettlement(
            PaymentInstruction.Status initialStatus
    ) {

        /*
         * Use real entities for accounts and the instruction.
         * This allows their state to change naturally during
         * execution of the service.
         */

        User realSenderUser = mock(User.class);

        when(realSenderUser.getEmail())
                .thenReturn(SENDER_EMAIL);

        User realReceiverUser = mock(User.class);


        Account realSender = new Account(
                realSenderUser,
                "1111111111111111"
        );

        Account realReceiver = new Account(
                realReceiverUser,
                "2222222222222222"
        );


        ReflectionTestUtils.setField(
                realSender,
                "id",
                1L
        );

        ReflectionTestUtils.setField(
                realReceiver,
                "id",
                2L
        );


        realSender.setBalance(
                new BigDecimal("1000.00")
        );

        realReceiver.setBalance(
                new BigDecimal("500.00")
        );


        LocalDateTime expiry = LocalDateTime.now().plusHours(1);

        PaymentInstruction realInstruction = new PaymentInstruction(
                realSender,
                realReceiver,
                new BigDecimal("200.00"),
                expiry
        );


        realInstruction.setStatus(initialStatus);

        ReflectionTestUtils.setField(
                realInstruction,
                "id",
                10L
        );


        String realInstructionUuid =
                realInstruction.getInstructionUuid();


        when(
                paymentInstructionRepository
                        .findByInstructionUuidForUpdate(
                                realInstructionUuid
                        )
        ).thenReturn(Optional.of(realInstruction));


        when(accountRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(realSender));

        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(realReceiver));


        /*
         * Save and return the actual transaction created by
         * the service instead of replacing it with a mock.
         */

        List<Transaction> savedTransactions = new ArrayList<>();

        when(
                transactionRepository.saveAndFlush(
                        any(Transaction.class)
                )
        ).thenAnswer(invocation -> {

            Transaction transaction =
                    invocation.getArgument(0);

            savedTransactions.add(transaction);

            return transaction;
        });


        /*
         * Capture the actual ledger entries generated
         * by the service.
         */

        List<LedgerEntry> savedEntries = new ArrayList<>();

        when(
                ledgerEntryRepository.save(
                        any(LedgerEntry.class)
                )
        ).thenAnswer(invocation -> {

            LedgerEntry entry =
                    invocation.getArgument(0);

            savedEntries.add(entry);

            return entry;
        });


        // Execute the settlement service.

        PaymentSettlementResponse response =
                paymentSettlementService.settlePayment(
                        realInstructionUuid,
                        SENDER_EMAIL
                );


        // A successful settlement must create one transaction.

        assertEquals(
                1,
                savedTransactions.size()
        );


        return new SettlementResult(
                realSender,
                realReceiver,
                realInstruction,
                response,
                savedTransactions.get(0),
                List.copyOf(savedEntries)
        );
    }


    // ---------------------------------------------------------
    // SHARED SETUP FOR MOCK-BASED NEGATIVE TESTS
    // ---------------------------------------------------------

    private void stubOwnership() {

        stubInstructionLookup();

        when(instruction.getSenderAccount())
                .thenReturn(sender);

        when(sender.getUser())
                .thenReturn(senderUser);

        when(senderUser.getEmail())
                .thenReturn(SENDER_EMAIL);
    }


    private void stubInstructionLookup() {

        when(
                paymentInstructionRepository
                        .findByInstructionUuidForUpdate(
                                INSTRUCTION_UUID
                        )
        ).thenReturn(Optional.of(instruction));
    }


    /*
     * This helper configures a valid payment that can proceed
     * through the initial authorization and account checks.
     *
     * Lenient stubbing is used because individual negative
     * tests intentionally stop at different validation stages.
     */

    private void stubValidMockSettlement(
            String amountText,
            String senderBalanceText
    ) {

        stubOwnership();


        lenient().when(instruction.getStatus())
                .thenReturn(PaymentInstruction.Status.CREATED);

        lenient().when(instruction.getExpiresAt())
                .thenReturn(LocalDateTime.now().plusHours(1));

        lenient().when(instruction.getReceiverAccount())
                .thenReturn(receiver);

        lenient().when(instruction.getAmount())
                .thenReturn(
                        amountText == null
                                ? null
                                : new BigDecimal(amountText)
                );

        lenient().when(instruction.getCurrency())
                .thenReturn("INR");


        lenient().when(sender.getId())
                .thenReturn(1L);

        lenient().when(sender.getStatus())
                .thenReturn(Account.Status.ACTIVE);

        lenient().when(sender.getCurrency())
                .thenReturn("INR");

        lenient().when(sender.getBalance())
                .thenReturn(
                        new BigDecimal(senderBalanceText)
                );


        lenient().when(receiver.getId())
                .thenReturn(2L);

        lenient().when(receiver.getStatus())
                .thenReturn(Account.Status.ACTIVE);

        lenient().when(receiver.getCurrency())
                .thenReturn("INR");


        lenient().when(accountRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(sender));

        lenient().when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(receiver));
    }


    // ---------------------------------------------------------
    // SHARED ASSERTION HELPERS
    // ---------------------------------------------------------

    private static ResponseStatusException expectStatus(
            HttpStatus expectedStatus,
            Executable executable
    ) {

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                executable
        );

        assertEquals(
                expectedStatus,
                exception.getStatusCode()
        );

        return exception;
    }


    private static void assertMoneyEquals(
            String expected,
            BigDecimal actual
    ) {

        assertNotNull(actual, "Expected monetary value must not be null");

        assertEquals(
                0,
                new BigDecimal(expected).compareTo(actual),
                "Expected monetary value: " + expected
                        + ", actual value: " + actual
        );
    }


    // ---------------------------------------------------------
    // RESULT HOLDER FOR SUCCESSFUL SETTLEMENT TESTS
    // ---------------------------------------------------------

    private record SettlementResult(
            Account sender,
            Account receiver,
            PaymentInstruction instruction,
            PaymentSettlementResponse response,
            Transaction transaction,
            List<LedgerEntry> ledgerEntries
    ) {
    }
}