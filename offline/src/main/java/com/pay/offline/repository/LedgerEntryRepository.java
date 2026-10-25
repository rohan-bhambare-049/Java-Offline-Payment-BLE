package com.pay.offline.repository;

import com.pay.offline.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(Long transactionId);

    boolean existsByTransactionIdAndAccountId(
            Long transactionId,
            Long accountId
    );

}
