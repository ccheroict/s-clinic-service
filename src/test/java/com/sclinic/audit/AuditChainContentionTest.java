package com.sclinic.audit;

import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the single chain-head row costs under concurrency.
 *
 * <p>Every audited write takes a row lock on the one {@code audit_chain_head}
 * row, so audited operations serialise against each other by design: two
 * concurrent writers reading the same head would fork the chain and leave two
 * entries claiming the same position. The review of task 1.9c flagged that as a
 * performance question to be answered by measurement rather than guesswork, and
 * batch B then added an audit write to the patient list — the most frequently
 * opened screen in the application — which is the point at which the question
 * stopped being theoretical.
 *
 * <p>This measures it. The assertions are about correctness, because that is
 * what can be asserted without flaking: every entry arrives, the chain verifies,
 * and the recorded count matches. The throughput is logged rather than asserted,
 * so a run on a developer's laptop does not fail the build for being a laptop.
 * The one timing assertion is a generous ceiling, there to catch serialisation
 * collapsing into lock timeouts rather than to police milliseconds.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-CONT-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
class AuditChainContentionTest {

    private static final Logger log = LoggerFactory.getLogger(AuditChainContentionTest.class);

    private static final int THREADS = 8;
    private static final int WRITES_PER_THREAD = 25;
    private static final int TOTAL_WRITES = THREADS * WRITES_PER_THREAD;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("audit_contention_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    AuditService auditService;

    @Autowired
    AuditChainVerifier verifier;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTrail() {
        // The append-only trigger blocks ordinary deletes, which is the point of
        // it. Disabled for the length of the reset only: this test needs a known
        // starting count, and the trigger's own tests cover that it works.
        jdbcTemplate.execute("alter table audit_log disable trigger all");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.execute("alter table audit_log enable trigger all");
        jdbcTemplate.update("update audit_chain_head set head_hash = null, entry_count = 0 "
                + "where id = 1");
    }

    @Test
    void concurrentAuditedWritesAllArriveAndTheChainStillVerifies() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(THREADS);

        for (int thread = 0; thread < THREADS; thread++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                for (int i = 0; i < WRITES_PER_THREAD; i++) {
                    // The shape batch B added: a list read, which carries no
                    // entity id and a small detail map.
                    auditService.record(AuditAction.VIEW, "patient", null,
                            AuditDetail.builder()
                                    .code("scope", "list")
                                    .code("results", "20")
                                    .code("filtered", "false")
                                    .build());
                }
                return null;
            }));
        }

        long startedAt = System.nanoTime();
        startTogether.countDown();

        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        pool.shutdown();

        // ---------- correctness, which is what gets asserted ----------

        Long written = jdbcTemplate.queryForObject(
                "select count(*) from audit_log", Long.class);
        assertThat(written)
                .withFailMessage("entries were lost under concurrency")
                .isEqualTo(TOTAL_WRITES);

        Long recordedCount = jdbcTemplate.queryForObject(
                "select entry_count from audit_chain_head where id = 1", Long.class);
        assertThat(recordedCount)
                .withFailMessage("the head row disagrees with the table")
                .isEqualTo(TOTAL_WRITES);

        // Each entry must link to exactly the one before it. A forked chain
        // would leave two entries sharing a prev_hash, and this is what would
        // catch that.
        AuditChainVerifier.ChainVerification verification = verifier.verify();
        assertThat(verification.intact())
                .withFailMessage("chain broken after concurrent writes: %s",
                        verification.problem())
                .isTrue();
        assertThat(verification.checkedEntries()).isEqualTo(TOTAL_WRITES);

        Long distinctPrevHashes = jdbcTemplate.queryForObject(
                "select count(distinct prev_hash) from audit_log where prev_hash is not null",
                Long.class);
        // Every entry but the first has a distinct predecessor; a fork would
        // reuse one.
        assertThat(distinctPrevHashes).isEqualTo(TOTAL_WRITES - 1);

        // ---------- the measurement, which gets logged ----------

        double perSecond = TOTAL_WRITES / (elapsed.toMillis() / 1000.0);
        log.info("AUDIT CHAIN CONTENTION: {} writes from {} threads in {} ms "
                        + "= {} writes/sec, {} ms per write",
                TOTAL_WRITES, THREADS, elapsed.toMillis(),
                Math.round(perSecond), elapsed.toMillis() / (double) TOTAL_WRITES);

        // A ceiling, not a target. Serialised writes against a local database
        // should be nowhere near this; the number exists so that the chain-head
        // lock degenerating into timeouts fails the build instead of merely
        // making the clinic slow.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void writesSerialiseWithoutDeadlockingAgainstEachOther() throws Exception {
        // The lock is only ever taken on this one table and the audit
        // transaction touches no business rows, which is what makes it unable to
        // deadlock against the transaction that triggered it. Worth a test of
        // its own: the reasoning is in a comment on AuditService, and a comment
        // does not fail when someone adds a second table to that transaction.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int thread = 0; thread < 4; thread++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                for (int i = 0; i < 10; i++) {
                    auditService.record(AuditAction.UPDATE, "patient", UUID.randomUUID());
                }
                return null;
            }));
        }

        startTogether.countDown();
        for (Future<?> future : futures) {
            // A deadlock would surface here as a timeout or an exception rather
            // than as a wrong number.
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(verifier.verify().intact()).isTrue();
    }
}
