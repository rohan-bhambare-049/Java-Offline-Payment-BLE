package com.pay.offline.mesh;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First line duplicate suppression, in memory.
 *
 * The reference project uses exactly this idea: an atomic
 * putIfAbsent on the ciphertext hash, equivalent to Redis SET NX.
 * Even if many bridge phones upload the same packet at the same
 * instant, exactly one caller wins the claim and only that caller
 * proceeds to decryption and settlement. The rest are dropped as
 * duplicates before any RSA work and before any ledger work.
 *
 * This is a fast, in memory layer only. It does not survive a
 * restart, and it does not replace the database layers. Durable
 * idempotency comes from the unique instruction UUID and the unique
 * packet hash in the mesh packet receipts table, which are checked
 * in the settlement step. If the in memory claim is lost on restart,
 * a replayed packet is still caught there, and a settled payment is
 * still returned as settled instead of being settled twice.
 *
 * Claims for packets that fail decryption, parsing, hash
 * verification, or freshness are released by the ingestion service,
 * so a failed packet does not permanently block its hash. Claims
 * for packets that passed those checks are kept, because the packet
 * is authentic and must only settle once.
 */
@Service
public class MeshPacketClaimService {

    private static final Duration CLAIM_AGE_LIMIT = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Instant> claimed = new ConcurrentHashMap<>();

    public boolean tryClaim(String packetHash) {
        return claimed.putIfAbsent(packetHash, Instant.now()) == null;
    }

    public void release(String packetHash) {
        claimed.remove(packetHash);
    }

    public void clear() {
        claimed.clear();
    }

    public boolean isClaimed(String packetHash) {
        return claimed.containsKey(packetHash);
    }

    public void evictExpired() {
        Instant cutoff = Instant.now().minus(CLAIM_AGE_LIMIT);
        claimed.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void evictExpiredScheduled() {
        evictExpired();
    }
}
