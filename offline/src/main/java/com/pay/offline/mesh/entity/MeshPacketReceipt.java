package com.pay.offline.mesh.entity;

import com.pay.offline.mesh.dto.MeshIngestionOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable record of one packet that reached the server.
 *
 * The unique constraint on packet_hash is the database layer of
 * idempotency. Even if the in memory claim is lost, for example on
 * a restart, or even if two server instances receive the same packet
 * at the same time, the database will refuse a second receipt row
 * for the same hash. The settlement step also checks the instruction
 * UUID, so a duplicate is caught even before a receipt row is
 * written.
 *
 * A receipt is only written for a packet that passed decryption,
 * payload hash verification, and freshness, and then reached a
 * settlement decision: settled, rejected, or expired. Invalid
 * packets get no receipt, because an invalid packet must not block
 * a later valid packet, and because its hash is not a hash of a
 * real payment.
 */
@Entity
@Table(
        name = "mesh_packet_receipts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_mesh_packet_hash",
                        columnNames = "packet_hash"
                )
        },
        indexes = {
                @Index(
                        name = "idx_mesh_packet_id",
                        columnList = "packet_id"
                ),
                @Index(
                        name = "idx_mesh_packet_instruction",
                        columnList = "instruction_uuid"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeshPacketReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "packet_hash", nullable = false, unique = true, length = 64)
    private String packetHash;

    @Column(name = "packet_id", nullable = false, length = 36)
    private String packetId;

    @Column(name = "origin_device_id", nullable = false, length = 100)
    private String originDeviceId;

    @Column(name = "bridge_node_id", length = 100)
    private String bridgeNodeId;

    @Column(name = "hop_count", nullable = false)
    private int hopCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private MeshIngestionOutcome outcome;

    @Column(name = "instruction_uuid", length = 36)
    private String instructionUuid;

    @Column(
            name = "received_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime receivedAt;

    public MeshPacketReceipt(
            String packetHash,
            UUID packetId,
            String originDeviceId,
            String bridgeNodeId,
            int hopCount,
            MeshIngestionOutcome outcome,
            String instructionUuid
    ) {
        this.packetHash = packetHash;
        this.packetId = packetId.toString();
        this.originDeviceId = originDeviceId;
        this.bridgeNodeId = bridgeNodeId;
        this.hopCount = hopCount;
        this.outcome = outcome;
        this.instructionUuid = instructionUuid;
    }
}
