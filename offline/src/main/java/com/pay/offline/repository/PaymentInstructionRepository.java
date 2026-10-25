package com.pay.offline.repository;

import com.pay.offline.entity.PaymentInstruction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentInstructionRepository
        extends JpaRepository<PaymentInstruction, Long> {

    Optional<PaymentInstruction> findByInstructionUuid(String instructionUuid);

    boolean existsByInstructionUuid(String instructionUuid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p
        FROM PaymentInstruction p
        WHERE p.instructionUuid = :instructionUuid
        """)
    Optional<PaymentInstruction> findByInstructionUuidForUpdate(
            @Param("instructionUuid") String instructionUuid
    );

}