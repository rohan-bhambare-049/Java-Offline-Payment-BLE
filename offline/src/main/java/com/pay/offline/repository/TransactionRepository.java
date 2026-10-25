package com.pay.offline.repository;

import com.pay.offline.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionUuid(String transactionUuid);

    Optional<Transaction> findByPaymentInstructionId(Long paymentInstructionId);

    boolean existsByPaymentInstructionId(Long paymentInstructionId);

}
