package com.pay.offline.mesh.dto;

/**
 * Every outcome a bridge upload can return, in plain words.
 *
 * SETTLED means the money moved exactly once in this delivery.
 * DUPLICATE_DROPPED means this packet was already claimed or already
 * settled, so no money moved in this delivery. It is not an error
 * for the bridge phone: in a mesh, duplicates are expected.
 * REJECTED means the payment was understood but cannot settle, for
 * example insufficient funds. The instruction is recorded as
 * rejected so a replay returns the same rejection instead of
 * retrying the debit.
 * EXPIRED means the packet is older than the allowed age, or its
 * creation time is too far in the future to trust.
 * INVALID means the packet could not be trusted at all: bad format,
 * failed decryption, failed payload hash, or an account that does
 * not exist. No instruction is created for an invalid packet.
 */
public enum MeshIngestionOutcome {
    SETTLED,
    DUPLICATE_DROPPED,
    REJECTED,
    EXPIRED,
    INVALID
}
