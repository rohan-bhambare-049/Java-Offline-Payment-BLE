package com.pay.offline.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "transactions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_transaction_uuid",
                        columnNames = "transaction_uuid"
                )
        },
        indexes = {
                @Index(
                        name = "idx_transaction_payment",
                        columnList = "payment_instruction_id"
                ),
                @Index(
                        name = "idx_transaction_status_created",
                        columnList = "status, created_at"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "transaction_uuid",
            nullable = false,
            unique = true,
            length = 36
    )
    private String transactionUuid;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_instruction_id",
            nullable = false,
            unique = true
    )
    private PaymentInstruction paymentInstruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PROCESSING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        PROCESSING,
        SUCCESS,
        FAILED,
        REJECTED
    }

    public Transaction(PaymentInstruction paymentInstruction) {
        this.transactionUuid = UUID.randomUUID().toString();
        this.paymentInstruction = paymentInstruction;
        this.status = Status.PROCESSING;
    }

}