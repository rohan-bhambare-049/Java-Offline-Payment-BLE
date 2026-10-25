package com.pay.offline.mesh.dto;

public record MeshIngestionResult(
        MeshIngestionOutcome outcome,
        String packetHash,
        String instructionUuid,
        String transactionUuid,
        String message
) {
    public static MeshIngestionResult settled(
            String packetHash,
            String instructionUuid,
            String transactionUuid
    ) {
        return new MeshIngestionResult(
                MeshIngestionOutcome.SETTLED,
                packetHash,
                instructionUuid,
                transactionUuid,
                "Payment settled successfully"
        );
    }

    public static MeshIngestionResult duplicate(
            String packetHash,
            String instructionUuid,
            String message
    ) {
        return new MeshIngestionResult(
                MeshIngestionOutcome.DUPLICATE_DROPPED,
                packetHash,
                instructionUuid,
                null,
                message
        );
    }

    public static MeshIngestionResult rejected(
            String packetHash,
            String instructionUuid,
            String message
    ) {
        return new MeshIngestionResult(
                MeshIngestionOutcome.REJECTED,
                packetHash,
                instructionUuid,
                null,
                message
        );
    }

    public static MeshIngestionResult expired(String packetHash, String message) {
        return new MeshIngestionResult(
                MeshIngestionOutcome.EXPIRED,
                packetHash,
                null,
                null,
                message
        );
    }

    public static MeshIngestionResult invalid(String packetHash, String message) {
        return new MeshIngestionResult(
                MeshIngestionOutcome.INVALID,
                packetHash,
                null,
                null,
                message
        );
    }
}
