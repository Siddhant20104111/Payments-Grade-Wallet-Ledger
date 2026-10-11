package com.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.dto.CreateTransferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Milestone 3: Idempotency & Safe Retries Acceptance Tests")
class Milestone3IdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Test 1: The same request sent 5 times sequentially creates 1 transfer and returns 5 identical responses")
    void sequentialRetriesReturnCachedResponse() throws Exception {
        String u = uid();
        Long userA = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Seq User A', 'seq.a_" + u + "@idem.com', '+91951" + u + "') RETURNING id",
                Long.class
        );
        Long userB = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Seq User B', 'seq.b_" + u + "@idem.com', '+91952" + u + "') RETURNING id",
                Long.class
        );
        Long accA = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-SEQ-A-" + u + "', 'USER', 'Wallet A', 100000) RETURNING id",
                Long.class, userA
        );
        Long accB = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-SEQ-B-" + u + "', 'USER', 'Wallet B', 0) RETURNING id",
                Long.class, userB
        );

        String idempotencyKey = UUID.randomUUID().toString();
        CreateTransferRequest request = new CreateTransferRequest(accA, accB, 20000L, "PEER_TRANSFER", "Lunch payment", userA);
        String requestJson = objectMapper.writeValueAsString(request);

        String firstResponseBody = null;

        for (int i = 1; i <= 5; i++) {
            MvcResult result = mockMvc.perform(post("/transfers")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseContent = result.getResponse().getContentAsString();
            if (i == 1) {
                firstResponseBody = responseContent;
            } else {
                assertEquals(firstResponseBody, responseContent, "Retry " + i + " must return exact identical cached response!");
            }
        }

        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?",
                Integer.class, idempotencyKey
        );
        assertEquals(1, transferCount, "Exactly 1 transfer record must exist in DB despite 5 retries");

        Long finalBalA = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accA);
        Long finalBalB = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accB);
        assertEquals(80000L, finalBalA, "Wallet A must be debited only once");
        assertEquals(20000L, finalBalB, "Wallet B must be credited only once");
    }

    @Test
    @DisplayName("Test 2: The same key sent 10 times in parallel creates exactly 1 transfer")
    void parallelRetriesCreateExactlyOneTransfer() throws Exception {
        String u = uid();
        Long userA = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Par User A', 'par.a_" + u + "@idem.com', '+91953" + u + "') RETURNING id",
                Long.class
        );
        Long userB = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Par User B', 'par.b_" + u + "@idem.com', '+91954" + u + "') RETURNING id",
                Long.class
        );
        Long accA = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-PAR-A-" + u + "', 'USER', 'Wallet A', 100000) RETURNING id",
                Long.class, userA
        );
        Long accB = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-PAR-B-" + u + "', 'USER', 'Wallet B', 0) RETURNING id",
                Long.class, userB
        );

        String idempotencyKey = UUID.randomUUID().toString();
        CreateTransferRequest request = new CreateTransferRequest(accA, accB, 15000L, "PEER_TRANSFER", "Split bill", userA);
        String requestJson = objectMapper.writeValueAsString(request);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/transfers")
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson))
                            .andReturn();
                    if (res.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?",
                Integer.class, idempotencyKey
        );
        assertEquals(1, transferCount, "Exactly 1 transfer record must exist in DB");

        Long finalBalA = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accA);
        Long finalBalB = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accB);
        assertEquals(85000L, finalBalA, "Wallet A must have lost only 15,000 paise");
        assertEquals(15000L, finalBalB, "Wallet B must have received only 15,000 paise");
    }

    @Test
    @DisplayName("Test 3: The same key with a different amount returns 422 and no money moves")
    void keyReusedWithDifferentPayloadReturns422() throws Exception {
        String u = uid();
        Long userA = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Diff User A', 'diff.a_" + u + "@idem.com', '+91955" + u + "') RETURNING id",
                Long.class
        );
        Long userB = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Diff User B', 'diff.b_" + u + "@idem.com', '+91956" + u + "') RETURNING id",
                Long.class
        );
        Long accA = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-DIFF-A-" + u + "', 'USER', 'Wallet A', 50000) RETURNING id",
                Long.class, userA
        );
        Long accB = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-DIFF-B-" + u + "', 'USER', 'Wallet B', 0) RETURNING id",
                Long.class, userB
        );

        String sharedKey = UUID.randomUUID().toString();

        CreateTransferRequest request1 = new CreateTransferRequest(accA, accB, 10000L, "PEER_TRANSFER", "Initial", userA);
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        Long balAAfterFirst = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accA);
        Long balBAfterFirst = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accB);
        assertEquals(40000L, balAAfterFirst);
        assertEquals(10000L, balBAfterFirst);

        CreateTransferRequest request2 = new CreateTransferRequest(accA, accB, 25000L, "PEER_TRANSFER", "Tampered amount", userA);
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));

        Long finalBalA = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accA);
        Long finalBalB = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accB);
        assertEquals(balAAfterFirst, finalBalA, "Wallet A balance must remain unchanged after rejected 422 retry");
        assertEquals(balBAfterFirst, finalBalB, "Wallet B balance must remain unchanged after rejected 422 retry");
    }

    @Test
    @DisplayName("Test 4: Missing Idempotency-Key header returns 400 Bad Request")
    void missingIdempotencyKeyReturns400() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 1000L, "PEER_TRANSFER", "Test", 1L);
        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("Test 5: Balance and Paginated Statement endpoints work correctly")
    void balanceAndStatementEndpoints() throws Exception {
        String u = uid();
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO users (name, email, phone) VALUES ('Stmt User', 'stmt_" + u + "@idem.com', '+91957" + u + "') RETURNING id",
                Long.class
        );
        Long acc = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, account_number, type, name, balance) VALUES (?, 'ACC-STMT-" + u + "', 'USER', 'Stmt Wallet', 75000) RETURNING id",
                Long.class, user
        );

        mockMvc.perform(get("/accounts/" + acc + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(75000))
                .andExpect(jsonPath("$.balanceRupees").value(750.0));

        mockMvc.perform(get("/accounts/" + acc + "/statement?limit=10&offset=0"))
                .andExpect(status().isOk());
    }
}
