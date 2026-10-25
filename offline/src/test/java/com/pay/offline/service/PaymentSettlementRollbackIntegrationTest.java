package com.pay.offline.service;

import com.pay.offline.entity.Account;
import com.pay.offline.entity.LedgerEntry;
import com.pay.offline.entity.PaymentInstruction;
import com.pay.offline.entity.Transaction;
import com.pay.offline.entity.User;
import com.pay.offline.repository.LedgerEntryRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.pay.offline.dto.PaymentSettlementResponse;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;


/**
 * Integration tests for database transaction rollback during
 * payment settlement.
 *
 * This test uses the application's configured database.
 *
 * It deliberately throws an unchecked exception after the
 * settlement's database changes have been flushed.
 *
 * The database must then roll back:
 *
 * 1. The transaction record.
 * 2. The sender's balance update.
 * 3. The receiver's balance update.
 * 4. The debit ledger entry.
 * 5. The credit ledger entry.
 * 6. The payment instruction status update.
 *
 * This test intentionally does not use @Transactional at the
 * test-class or test-method level. The settlement service must
 * manage its own transaction so we can inspect the database
 * after that transaction has finished rolling back.
 */
@SpringBootTest
class PaymentSettlementRollbackIntegrationTest {

    private static final BigDecimal PAYMENT_AMOUNT =
            new BigDecimal("200.00");

    private static final BigDecimal INITIAL_SENDER_BALANCE =
            new BigDecimal("1000.00");

    private static final BigDecimal INITIAL_RECEIVER_BALANCE =
            new BigDecimal("500.00");

    private static final String SIMULATED_FAILURE_MESSAGE =
            "Simulated failure after settlement changes were flushed";


    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PaymentSettlementService paymentSettlementService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /*
     * Spy on the real repository bean.
     *
     * Normal repository operations continue to use the database.
     * Only the final ledger repository flush is configured to fail.
     */
    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;


    private User senderUser;

    private User receiverUser;

    private Account senderAccount;

    private Account receiverAccount;

    private PaymentInstruction paymentInstruction;


    private Long senderUserId;

    private Long receiverUserId;

    private Long senderAccountId;

    private Long receiverAccountId;

    private Long paymentInstructionId;

    private String instructionUuid;

    private String senderEmail;


    // ---------------------------------------------------------
    // CREATE REAL DATABASE FIXTURES
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldPersistExpiredStatusWithoutCreatingTransaction() {

        /*
         * Arrange:
         * Expire the existing test instruction in its own transaction.
         */
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {

            PaymentInstruction persistedInstruction =
                    entityManager.find(
                            PaymentInstruction.class,
                            paymentInstructionId
                    );

            assertNotNull(persistedInstruction);

            persistedInstruction.setExpiresAt(
                    LocalDateTime.now().minusMinutes(1)
            );

            entityManager.flush();
        });


        /*
         * Act:
         * The service should return an expired result normally.
         * Its transaction should commit the status update.
         */
        PaymentSettlementResponse response =
                paymentSettlementService.settlePayment(
                        instructionUuid,
                        senderEmail
                );


        // The service response must indicate expiry.

        assertEquals(
                PaymentInstruction.Status.EXPIRED.name(),
                response.instructionStatus()
        );

        assertEquals(
                "Payment instruction has expired",
                response.message()
        );

        assertNull(response.transactionUuid());


        /*
         * Assert:
         * Inspect the actual database after the settlement transaction
         * has completed.
         */
        DatabaseSnapshot snapshot = readDatabaseSnapshot();

        assertNotNull(snapshot);

        assertMoneyEquals(
                INITIAL_SENDER_BALANCE,
                snapshot.senderBalance(),
                "The sender balance must remain unchanged"
        );

        assertMoneyEquals(
                INITIAL_RECEIVER_BALANCE,
                snapshot.receiverBalance(),
                "The receiver balance must remain unchanged"
        );

        assertEquals(
                PaymentInstruction.Status.EXPIRED,
                snapshot.instructionStatus(),
                "The expired status must remain persisted in MySQL"
        );

        assertEquals(
                0L,
                snapshot.transactionCount(),
                "An expired payment must not create a transaction"
        );

        assertEquals(
                0L,
                snapshot.ledgerEntryCount(),
                "An expired payment must not create ledger entries"
        );
    }

    @BeforeEach
    void setUpDatabaseFixture() {

        String uniqueSuffix = UUID.randomUUID().toString();

        senderEmail = "rollback-sender-" + uniqueSuffix
                + "@example.com";

        String receiverEmail = "rollback-receiver-" + uniqueSuffix
                + "@example.com";


        senderUser = createTestUser(
                "Rollback Test Sender",
                senderEmail
        );

        receiverUser = createTestUser(
                "Rollback Test Receiver",
                receiverEmail
        );


        senderAccount = new Account(
                senderUser,
                generateAccountNumber()
        );

        senderAccount.setBalance(
                INITIAL_SENDER_BALANCE
        );

        senderAccount.setCurrency("INR");

        senderAccount.setStatus(
                Account.Status.ACTIVE
        );


        receiverAccount = new Account(
                receiverUser,
                generateAccountNumber()
        );

        receiverAccount.setBalance(
                INITIAL_RECEIVER_BALANCE
        );

        receiverAccount.setCurrency("INR");

        receiverAccount.setStatus(
                Account.Status.ACTIVE
        );


        LocalDateTime expiryTime =
                LocalDateTime.now().plusHours(1);


        paymentInstruction = new PaymentInstruction(
                senderAccount,
                receiverAccount,
                PAYMENT_AMOUNT,
                expiryTime
        );

        paymentInstruction.setCurrency("INR");

        paymentInstruction.setStatus(
                PaymentInstruction.Status.CREATED
        );


        /*
         * Commit the test fixture before calling the settlement
         * service.
         *
         * This is important: a later failure in the settlement
         * transaction must not roll back the fixture itself.
         */
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {

            entityManager.persist(senderUser);

            entityManager.persist(receiverUser);

            entityManager.flush();


            entityManager.persist(senderAccount);

            entityManager.persist(receiverAccount);

            entityManager.flush();


            entityManager.persist(paymentInstruction);

            entityManager.flush();
        });


        /*
         * Capture the generated database IDs after the fixture
         * transaction has completed.
         */
        senderUserId = senderUser.getId();

        receiverUserId = receiverUser.getId();

        senderAccountId = senderAccount.getId();

        receiverAccountId = receiverAccount.getId();

        paymentInstructionId = paymentInstruction.getId();

        instructionUuid = paymentInstruction.getInstructionUuid();


        assertNotNull(senderUserId);

        assertNotNull(receiverUserId);

        assertNotNull(senderAccountId);

        assertNotNull(receiverAccountId);

        assertNotNull(paymentInstructionId);

        assertNotNull(instructionUuid);
    }


    // ---------------------------------------------------------
    // TEST: SETTLEMENT CHANGES MUST ROLL BACK ON FAILURE
    // ---------------------------------------------------------

    @Test
    void settlePaymentShouldRollbackAllDatabaseChangesWhenFailureOccurs()
            throws Exception {

        /*
         * Arrange
         *
         * The settlement service calls repository flush operations
         * in the following order:
         *
         * 1. transactionRepository.flush()
         * 2. accountRepository.flush()
         * 3. ledgerEntryRepository.flush()
         * 4. paymentInstructionRepository.flush()
         *
         * A JPA EntityManager flush synchronizes pending changes
         * across its persistence context, not only changes to one
         * repository's entity type.
         *
         * Therefore, by the time the third flush is reached, the
         * earlier transaction and account flush calls have already
         * synchronized the changes to the database.
         *
         * They have NOT committed the transaction.
         *
         * We simulate an unchecked failure at this point.
         * Spring should roll back the complete settlement transaction.
         */
        doThrow(
                new IllegalStateException(
                        SIMULATED_FAILURE_MESSAGE
                )
        ).when(ledgerEntryRepository).flush();


        /*
         * Act
         *
         * Invoke the actual Spring-managed service bean.
         *
         * Do not instantiate PaymentSettlementService manually.
         * The Spring transactional proxy must remain active.
         */
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> paymentSettlementService.settlePayment(
                        instructionUuid,
                        senderEmail
                )
        );


        assertEquals(
                SIMULATED_FAILURE_MESSAGE,
                exception.getMessage()
        );


        /*
         * Confirm that execution reached the deliberately failing
         * flush operation.
         */
        verify(ledgerEntryRepository).flush();


        /*
         * Assert
         *
         * Read the database in a separate transaction after the
         * settlement transaction has finished.
         *
         * This prevents uncommitted state in the settlement's
         * persistence context from affecting the assertions.
         */
        DatabaseSnapshot snapshot = readDatabaseSnapshot();


        assertNotNull(snapshot);


        // 1. Sender balance must be unchanged.

        assertMoneyEquals(
                INITIAL_SENDER_BALANCE,
                snapshot.senderBalance(),
                "Sender balance should be restored after rollback"
        );


        // 2. Receiver balance must be unchanged.

        assertMoneyEquals(
                INITIAL_RECEIVER_BALANCE,
                snapshot.receiverBalance(),
                "Receiver balance should be restored after rollback"
        );


        // 3. The payment instruction must remain CREATED.

        assertEquals(
                PaymentInstruction.Status.CREATED,
                snapshot.instructionStatus(),
                "Payment instruction status should be restored after rollback"
        );


        // 4. No transaction record should remain.

        assertEquals(
                0L,
                snapshot.transactionCount(),
                "Transaction record should be rolled back"
        );


        // 5. No ledger entries should remain.

        assertEquals(
                0L,
                snapshot.ledgerEntryCount(),
                "Ledger entries should be rolled back"
        );
    }


    // ---------------------------------------------------------
    // READ THE RESULT FROM A FRESH DATABASE TRANSACTION
    // ---------------------------------------------------------

    private DatabaseSnapshot readDatabaseSnapshot() {

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        return transactionTemplate.execute(status -> {

            /*
             * Reload entities by ID. Do not inspect the original Java
             * objects because they may contain state from the failed
             * settlement transaction.
             */
            Account actualSender = entityManager.find(
                    Account.class,
                    senderAccountId
            );

            Account actualReceiver = entityManager.find(
                    Account.class,
                    receiverAccountId
            );

            PaymentInstruction actualInstruction =
                    entityManager.find(
                            PaymentInstruction.class,
                            paymentInstructionId
                    );


            assertNotNull(actualSender);

            assertNotNull(actualReceiver);

            assertNotNull(actualInstruction);


            Long transactionCount = entityManager.createQuery(
                            """
                            SELECT COUNT(t)
                            FROM Transaction t
                            WHERE t.paymentInstruction.id = :instructionId
                            """,
                            Long.class
                    )
                    .setParameter(
                            "instructionId",
                            paymentInstructionId
                    )
                    .getSingleResult();


            Long ledgerEntryCount = entityManager.createQuery(
                            """
                            SELECT COUNT(le)
                            FROM LedgerEntry le
                            WHERE le.transaction.paymentInstruction.id = :instructionId
                            """,
                            Long.class
                    )
                    .setParameter(
                            "instructionId",
                            paymentInstructionId
                    )
                    .getSingleResult();


            return new DatabaseSnapshot(
                    actualSender.getBalance(),
                    actualReceiver.getBalance(),
                    actualInstruction.getStatus(),
                    transactionCount,
                    ledgerEntryCount
            );
        });
    }


    // ---------------------------------------------------------
    // CREATE TEST USERS
    // ---------------------------------------------------------

    private User createTestUser(
            String fullName,
            String email
    ) {

        /*
         * Use the entity's no-argument constructor even if it is
         * protected, then populate the fields required by the
         * current entity mapping.
         *
         * This avoids assuming a public User constructor exists.
         */
        User user = BeanUtils.instantiateClass(User.class);

        ReflectionTestUtils.setField(
                user,
                "fullName",
                fullName
        );

        ReflectionTestUtils.setField(
                user,
                "email",
                email
        );

        ReflectionTestUtils.setField(
                user,
                "passwordHash",
                "rollback-integration-test-password-hash"
        );

        ReflectionTestUtils.setField(
                user,
                "role",
                User.Role.USER
        );

        ReflectionTestUtils.setField(
                user,
                "status",
                User.Status.ACTIVE
        );

        return user;
    }


    // ---------------------------------------------------------
    // GENERATE A UNIQUE 16-DIGIT ACCOUNT NUMBER
    // ---------------------------------------------------------

    private String generateAccountNumber() {

        long accountNumber =
                ThreadLocalRandom.current().nextLong(
                        1_000_000_000_000_000L,
                        9_999_999_999_999_999L
                );

        return Long.toString(accountNumber);
    }


    // ---------------------------------------------------------
    // MONEY ASSERTION
    // ---------------------------------------------------------

    private static void assertMoneyEquals(
            BigDecimal expected,
            BigDecimal actual,
            String message
    ) {

        assertNotNull(actual, message + ": actual value is null");

        assertEquals(
                0,
                expected.compareTo(actual),
                message
                        + ". Expected: "
                        + expected
                        + ", actual: "
                        + actual
        );
    }


    // ---------------------------------------------------------
    // DELETE ONLY THE TEST DATA CREATED BY THIS TEST
    // ---------------------------------------------------------

    @AfterEach
    void cleanUpDatabaseFixture() {

        if (paymentInstructionId == null) {
            return;
        }


        /*
         * Clean up in foreign-key order.
         *
         * The service transaction should have rolled back already.
         * This cleanup also handles unexpected partial commits if
         * an assertion fails while diagnosing the implementation.
         */
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);


        transactionTemplate.executeWithoutResult(status -> {

            List<Transaction> transactions =
                    entityManager.createQuery(
                                    """
                                    SELECT t
                                    FROM Transaction t
                                    WHERE t.paymentInstruction.id = :instructionId
                                    """,
                                    Transaction.class
                            )
                            .setParameter(
                                    "instructionId",
                                    paymentInstructionId
                            )
                            .getResultList();


            for (Transaction transaction : transactions) {

                List<LedgerEntry> entries =
                        entityManager.createQuery(
                                        """
                                        SELECT le
                                        FROM LedgerEntry le
                                        WHERE le.transaction.id = :transactionId
                                        """,
                                        LedgerEntry.class
                                )
                                .setParameter(
                                        "transactionId",
                                        transaction.getId()
                                )
                                .getResultList();


                for (LedgerEntry entry : entries) {
                    entityManager.remove(entry);
                }

                entityManager.remove(transaction);
            }


            PaymentInstruction persistedInstruction =
                    entityManager.find(
                            PaymentInstruction.class,
                            paymentInstructionId
                    );

            if (persistedInstruction != null) {
                entityManager.remove(persistedInstruction);
            }


            Account persistedSender =
                    entityManager.find(
                            Account.class,
                            senderAccountId
                    );

            if (persistedSender != null) {
                entityManager.remove(persistedSender);
            }


            Account persistedReceiver =
                    entityManager.find(
                            Account.class,
                            receiverAccountId
                    );

            if (persistedReceiver != null) {
                entityManager.remove(persistedReceiver);
            }


            User persistedSenderUser =
                    entityManager.find(
                            User.class,
                            senderUserId
                    );

            if (persistedSenderUser != null) {
                entityManager.remove(persistedSenderUser);
            }


            User persistedReceiverUser =
                    entityManager.find(
                            User.class,
                            receiverUserId
                    );

            if (persistedReceiverUser != null) {
                entityManager.remove(persistedReceiverUser);
            }


            entityManager.flush();
        });
    }


    // ---------------------------------------------------------
    // SNAPSHOT OF THE ACTUAL DATABASE STATE
    // ---------------------------------------------------------

    private record DatabaseSnapshot(
            BigDecimal senderBalance,
            BigDecimal receiverBalance,
            PaymentInstruction.Status instructionStatus,
            Long transactionCount,
            Long ledgerEntryCount
    ) {
    }
}