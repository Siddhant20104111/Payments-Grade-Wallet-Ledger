package com.wallet;

import com.wallet.domain.InsufficientFundsException;
import com.wallet.domain.TransferRequest;
import com.wallet.service.SafeTransferService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@DisplayName("Milestone 2 Acceptance Test: The 100-Transfer Stampede")
class StampedeAcceptanceTest {

    private static final Logger log = LoggerFactory.getLogger(StampedeAcceptanceTest.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SafeTransferService safeTransferService;

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    @DisplayName("Run the stampede test 20 consecutive times: 100 concurrent ₹10 transfers from ₹500 wallet")
    void executeStampedeAcceptanceTest20Times() throws InterruptedException {
        int totalRounds = 20;

        for (int round = 1; round <= totalRounds; round++) {
            runSingleStampedeRound(round);
        }
        log.info("🎉 SUCCESS: Stampede Acceptance Test passed all {} consecutive iterations!", totalRounds);
    }

    private void runSingleStampedeRound(int roundNumber) throws InterruptedException {
        String u = uid();
        Long userA = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Sender " + roundNumber + "', 's." + roundNumber + "_" + u + "@stamp.com', '+91971" + roundNumber + u + "') RETURNING id",
                Long.class
        );
        Long userB = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Receiver " + roundNumber + "', 'r." + roundNumber + "_" + u + "@stamp.com', '+91972" + roundNumber + u + "') RETURNING id",
                Long.class
        );

        Long accA = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-STP-A-" + roundNumber + "-" + u + "', 'USER', 'Wallet A', 50000) RETURNING id",
                Long.class, userA
        );
        Long accB = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-STP-B-" + roundNumber + "-" + u + "', 'USER', 'Wallet B', 0) RETURNING id",
                Long.class, userB
        );

        int totalTransfers = 100;
        int threadPoolSize = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finishSignal = new CountDownLatch(totalTransfers);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < totalTransfers; i++) {
            final int transferIndex = i;
            executor.submit(() -> {
                try {
                    startSignal.await();
                    safeTransferService.executeTransfer(
                            new TransferRequest(accA, accB, 1000L, "PEER_TRANSFER", "IDEM-" + roundNumber + "-" + transferIndex + "-" + u, "Stampede payment " + transferIndex, userA)
                    );
                    successes.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    log.error("Unexpected error in transfer {}: {}", transferIndex, e.getMessage(), e);
                    failures.incrementAndGet();
                } finally {
                    finishSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        finishSignal.await();
        executor.shutdown();

        assertEquals(50, successes.get(), "Round " + roundNumber + ": Exactly 50 transfers must succeed");
        assertEquals(50, failures.get(), "Round " + roundNumber + ": Exactly 50 transfers must fail");

        Long finalBalA = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accA);
        assertEquals(0L, finalBalA, "Round " + roundNumber + ": Wallet A must end at 0 paise");

        Long finalBalB = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accB);
        assertEquals(50000L, finalBalB, "Round " + roundNumber + ": Wallet B must end at 50,000 paise");

        Long totalDebits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ? AND entry_type = 'DEBIT'",
                Long.class, accA
        );
        Long totalCredits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ? AND entry_type = 'CREDIT'",
                Long.class, accB
        );
        assertEquals(50000L, totalDebits);
        assertEquals(50000L, totalCredits);
    }
}
