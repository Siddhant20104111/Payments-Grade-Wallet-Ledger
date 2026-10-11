package com.wallet;

import com.wallet.domain.TransferRequest;
import com.wallet.service.UnsafeTransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@DisplayName("Milestone 2 Step 2: Breaking It First - Concurrency Without Locks")
class UnsafeTransferConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(UnsafeTransferConcurrencyTest.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UnsafeTransferService unsafeTransferService;

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Demonstrate race condition / lost update when row locks are missing")
    void reproduceRaceConditionWithoutLocks() throws InterruptedException {
        String u = uid();
        Long userA = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Unsafe A', 'unsafe.a_" + u + "@test.com', '+91981" + u + "') RETURNING id",
                Long.class
        );
        Long userB = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Unsafe B', 'unsafe.b_" + u + "@test.com', '+91982" + u + "') RETURNING id",
                Long.class
        );

        Long accA = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-UNS-A-" + u + "', 'USER', 'Wallet A', 10000) RETURNING id",
                Long.class, userA
        );
        Long accB = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-UNS-B-" + u + "', 'USER', 'Wallet B', 0) RETURNING id",
                Long.class, userB
        );

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    unsafeTransferService.executeUnsafeTransfer(
                            new TransferRequest(accA, accB, 2000L, "PEER_TRANSFER", null, "Unsafe test", userA)
                    );
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        Long finalAccA = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accA);
        Long finalAccB = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accB);

        log.info("Unsafe run complete: {} successes, {} failures. Final Account A: {} paise, Final Account B: {} paise",
                successes.get(), failures.get(), finalAccA, finalAccB);
    }
}
