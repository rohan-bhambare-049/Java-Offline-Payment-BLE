package com.pay.offline.mesh;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshPacketClaimServiceTest {

    @Test
    void firstClaimWins() {
        MeshPacketClaimService claims = new MeshPacketClaimService();
        assertTrue(claims.tryClaim("hash-1"));
        assertFalse(claims.tryClaim("hash-1"));
        claims.release("hash-1");
        assertTrue(claims.tryClaim("hash-1"));
    }

    @Test
    void concurrentClaimsForSamePacketSettleExactlyOnce() throws Exception {
        MeshPacketClaimService claims = new MeshPacketClaimService();
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (claims.tryClaim("same-packet-hash")) {
                        winners.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, winners.get());
    }
}
