package com.pay.offline.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "payment_instructions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_instruction_uuid",
                        columnNames = "instruction_uuid"
                )
        },
        indexes = {
                @Index(
                        name = "idx_payment_sender",
                        columnList = "sender_account_id"
                ),
                @Index(
                        name = "idx_payment_receiver",
                        columnList = "receiver_account_id"
                ),
                @Index(
                        name = "idx_payment_status_created",
                        columnList = "status, created_at"
                ),
                @Index(
                        name = "idx_payment_expiration",
                        columnList = "expires_at"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "instruction_uuid",
            nullable = false,
            unique = true,
            length = 36
    )
    private String instructionUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_account_id", nullable = false)
    private Account senderAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_account_id", nullable = false)
    private Account receiverAccount;

    @Column(
            name = "amount",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.CREATED;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    public enum Status {
        CREATED,
        PENDING_SYNC,
        PROCESSING,
        SETTLED,
        REJECTED,
        EXPIRED
    }

    public PaymentInstruction(
            Account senderAccount,
            Account receiverAccount,
            BigDecimal amount,
            LocalDateTime expiresAt
    ) {
        this.instructionUuid = UUID.randomUUID().toString();
        this.senderAccount = senderAccount;
        this.receiverAccount = receiverAccount;
        this.amount = amount;
        this.currency = "INR";
        this.status = Status.CREATED;
        this.expiresAt = expiresAt;
    }
}