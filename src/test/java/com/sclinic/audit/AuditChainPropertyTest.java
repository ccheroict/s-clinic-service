package com.sclinic.audit;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for the audit hash chain.
 *
 * <p><b>Property: the chain detects every alteration or removal in the middle of
 * the trail.</b>
 *
 * <p>The adversary modelled here is the strongest one the chain is meant to
 * catch: someone with direct database access, who has already got past the
 * append-only triggers, and who is careful enough to leave the chain head record
 * consistent with what they left behind. The chain itself has to be what gives
 * them away.
 */
class AuditChainPropertyTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant BASE = Instant.parse("2026-08-29T03:00:00Z");

    // ---------- fixtures ----------

    /** A correctly chained trail of {@code size} entries. */
    private static List<AuditLog> chainOf(int size) {
        List<AuditLog> entries = new ArrayList<>(size);
        String prevHash = null;

        for (int i = 0; i < size; i++) {
            AuditLog entry = new AuditLog();
            // Spaced so a mutation test can insert between two entries.
            entry.setId((long) (i + 1) * 10);
            entry.setStaffId(STAFF);
            entry.setAction(i % 2 == 0 ? "VIEW" : "UPDATE");
            entry.setEntityType("patient");
            entry.setEntityId(new UUID(0, i));
            entry.setDetail(i % 3 == 0 ? null : Map.of("field" + i, "changed"));
            entry.setIp("10.0.0." + (i % 256));
            entry.setUserAgent("JUnit");
            entry.setSessionId(SESSION);
            entry.setCreatedAt(BASE.plusSeconds(i));
            entry.setPrevHash(prevHash);
            entry.setEntryHash(AuditHash.of(prevHash, entry));

            entries.add(entry);
            prevHash = entry.getEntryHash();
        }
        return entries;
    }

    /** A head record consistent with the given trail. */
    private static AuditChainHead headFor(List<AuditLog> entries) {
        AuditChainHead head = new AuditChainHead();
        head.setEntryCount(entries.size());
        head.setHeadHash(entries.isEmpty() ? null : entries.get(entries.size() - 1).getEntryHash());
        return head;
    }

    /**
     * Renumbers ids ascending by position.
     *
     * <p>A real table always hands rows back in id order, so "an entry was removed"
     * or "two entries were swapped" shows up as the surviving payloads sitting at
     * different id positions, not as ids out of order. Renumbering after a mutation
     * is what models the database state faithfully. It changes no hash: the id is
     * assigned by the database and is deliberately not part of what is hashed.
     */
    private static List<AuditLog> withAscendingIds(List<AuditLog> entries) {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setId((long) (i + 1) * 10);
        }
        return entries;
    }

    private static AuditChainVerifier verifierOver(List<AuditLog> entries, AuditChainHead head) {
        AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
        when(logs.countByEntryHashIsNull()).thenReturn(0L);
        when(logs.findByEntryHashIsNotNullAndIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenAnswer(invocation -> sliceOf(
                        entries, invocation.getArgument(0), invocation.getArgument(1)));

        AuditChainHeadRepository heads = Mockito.mock(AuditChainHeadRepository.class);
        when(heads.peekHead()).thenReturn(Optional.of(head));

        return new AuditChainVerifier(logs, heads);
    }

    /** Keyset slice, matching what the repository query does. */
    private static List<AuditLog> sliceOf(List<AuditLog> entries, long afterId, Limit limit) {
        return entries.stream()
                .filter(entry -> entry.getId() > afterId)
                .sorted(java.util.Comparator.comparing(AuditLog::getId))
                .limit(limit.max())
                .toList();
    }

    private static AuditChainVerifier.ChainVerification verify(List<AuditLog> entries) {
        return verifierOver(entries, headFor(entries)).verify();
    }

    // ---------- the tampering the property explores ----------

    /**
     * Every field an audit entry carries. Each one must be covered by the hash,
     * so changing any of them has to break the chain.
     */
    private enum Tampering {
        ACTION(entry -> entry.setAction("DELETE")),
        STAFF(entry -> entry.setStaffId(UUID.randomUUID())),
        ENTITY_TYPE(entry -> entry.setEntityType("something_else")),
        ENTITY_ID(entry -> entry.setEntityId(UUID.randomUUID())),
        DETAIL(entry -> entry.setDetail(Map.of("covered", "up"))),
        DETAIL_CLEARED(entry -> entry.setDetail(null)),
        IP(entry -> entry.setIp("127.0.0.1")),
        USER_AGENT(entry -> entry.setUserAgent("someone else")),
        SESSION(entry -> entry.setSessionId(UUID.randomUUID())),
        CREATED_AT(entry -> entry.setCreatedAt(entry.getCreatedAt().plusSeconds(3600))),
        PREV_HASH(entry -> entry.setPrevHash("0".repeat(64)));

        private final Consumer<AuditLog> apply;

        Tampering(Consumer<AuditLog> apply) {
            this.apply = apply;
        }

        void applyTo(AuditLog entry) {
            apply.accept(entry);
        }
    }

    @Provide
    Arbitrary<Tampering> tamperings() {
        return Arbitraries.of(Tampering.values());
    }

    // ---------- properties ----------

    @Property(tries = 200)
    @Tag("Feature: compliance-vn, Property: audit chain detects any alteration")
    void alteringAnyFieldOfAnyEntryBreaksTheChain(
            @ForAll @IntRange(min = 1, max = 40) int size,
            @ForAll("tamperings") Tampering tampering,
            @ForAll @IntRange(min = 0, max = 39) int targetSeed) {

        List<AuditLog> entries = chainOf(size);
        int target = targetSeed % size;
        AuditChainHead head = headFor(entries);
        AuditLog victim = entries.get(target);

        String before = AuditHash.of(victim.getPrevHash(), victim);
        tampering.applyTo(victim);
        String after = AuditHash.of(victim.getPrevHash(), victim);

        // Some tamperings are no-ops on some entries: clearing a detail that was
        // already null changes nothing, and an entry nobody altered has nothing
        // to detect. Those samples say nothing either way.
        Assume.that(!before.equals(after));

        AuditChainVerifier.ChainVerification result = verifierOver(entries, head).verify();

        assertThat(result.intact())
                .as("%s on entry %d of %d must be detected", tampering, target, size)
                .isFalse();
        assertThat(result.problem()).isNotBlank();
    }

    @Property(tries = 200)
    @Tag("Feature: compliance-vn, Property: audit chain detects any removal")
    void removingAnEntryFromTheMiddleBreaksTheChain(
            @ForAll @IntRange(min = 2, max = 40) int size,
            @ForAll @IntRange(min = 0, max = 38) int targetSeed) {

        List<AuditLog> entries = chainOf(size);
        // The last entry is excluded: nothing follows it, so removing it is the
        // end-of-chain case covered by the head record, not by a broken link.
        int target = targetSeed % (size - 1);

        List<AuditLog> tampered = new ArrayList<>(entries);
        tampered.remove(target);
        withAscendingIds(tampered);

        // A thorough adversary leaves the head record consistent with what
        // remains: the last entry is untouched, so only the count needs fixing.
        AuditChainHead head = new AuditChainHead();
        head.setHeadHash(entries.get(size - 1).getEntryHash());
        head.setEntryCount(tampered.size());

        AuditChainVerifier.ChainVerification result = verifierOver(tampered, head).verify();

        assertThat(result.intact())
                .as("removing entry %d of %d must be detected", target, size)
                .isFalse();
        assertThat(result.firstBrokenEntryId()).isNotNull();
    }

    @Property(tries = 100)
    @Tag("Feature: compliance-vn, Property: audit chain detects insertion")
    void insertingAnEntryInTheMiddleBreaksTheChain(
            @ForAll @IntRange(min = 1, max = 40) int size,
            @ForAll @IntRange(min = 0, max = 39) int targetSeed) {

        List<AuditLog> entries = chainOf(size);
        int at = targetSeed % size;

        AuditLog forged = new AuditLog();
        forged.setStaffId(UUID.randomUUID());
        forged.setAction("VIEW");
        forged.setEntityType("patient");
        forged.setEntityId(UUID.randomUUID());
        forged.setIp("10.9.9.9");
        forged.setUserAgent("forged");
        forged.setCreatedAt(BASE);
        // The forger links it to the entry it now follows and hashes it correctly,
        // so the forged entry itself is internally consistent.
        String prevHash = at == 0 ? null : entries.get(at - 1).getEntryHash();
        forged.setPrevHash(prevHash);
        forged.setEntryHash(AuditHash.of(prevHash, forged));

        List<AuditLog> tampered = new ArrayList<>(entries);
        tampered.add(at, forged);
        withAscendingIds(tampered);

        AuditChainHead head = new AuditChainHead();
        head.setHeadHash(entries.get(size - 1).getEntryHash());
        head.setEntryCount(tampered.size());

        AuditChainVerifier.ChainVerification result = verifierOver(tampered, head).verify();

        // The entry after the forged one still points at the entry that used to
        // precede it, so the break surfaces there.
        assertThat(result.intact()).isFalse();
    }

    @Property(tries = 100)
    @Tag("Feature: compliance-vn, Property: audit chain detects reordering")
    void swappingTwoEntriesBreaksTheChain(
            @ForAll @IntRange(min = 2, max = 40) int size,
            @ForAll @IntRange(min = 0, max = 38) int seed) {

        List<AuditLog> entries = chainOf(size);
        int first = seed % (size - 1);

        AuditChainHead head = headFor(entries);

        List<AuditLog> tampered = new ArrayList<>(entries);
        java.util.Collections.swap(tampered, first, first + 1);
        withAscendingIds(tampered);

        AuditChainVerifier.ChainVerification result = verifierOver(tampered, head).verify();

        assertThat(result.intact()).isFalse();
    }

    @Property(tries = 100)
    @Tag("Feature: compliance-vn, Property: an untouched chain always verifies")
    void anUntouchedChainAlwaysVerifies(@ForAll @IntRange(min = 0, max = 60) int size) {
        List<AuditLog> entries = chainOf(size);

        AuditChainVerifier.ChainVerification result = verify(entries);

        assertThat(result.intact()).isTrue();
        assertThat(result.checkedEntries()).isEqualTo(size);
        assertThat(result.problem()).isNull();
    }

    // ---------- the end-of-chain case, which needs the head record ----------

    @Test
    void removingEntriesFromTheEndIsDetectedByTheHeadRecord() {
        List<AuditLog> entries = chainOf(10);
        AuditChainHead head = headFor(entries);

        List<AuditLog> truncated = new ArrayList<>(entries.subList(0, 7));

        AuditChainVerifier.ChainVerification result = verifierOver(truncated, head).verify();

        assertThat(result.intact()).isFalse();
        assertThat(result.problem()).contains("removed from the end");
    }

    @Test
    void emptyingTheWholeTrailIsDetected() {
        List<AuditLog> entries = chainOf(5);
        AuditChainHead head = headFor(entries);

        AuditChainVerifier.ChainVerification result = verifierOver(List.of(), head).verify();

        assertThat(result.intact()).isFalse();
    }

    @Test
    void aMissingHeadRecordIsNotTreatedAsIntact() {
        AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
        AuditChainHeadRepository heads = Mockito.mock(AuditChainHeadRepository.class);
        when(heads.peekHead()).thenReturn(Optional.empty());

        AuditChainVerifier.ChainVerification result = new AuditChainVerifier(logs, heads).verify();

        assertThat(result.intact()).isFalse();
        assertThat(result.problem()).contains("head record is missing");
    }

    @Test
    void aPaddedCountIsDetectedEvenWhenEveryLinkHolds() {
        List<AuditLog> entries = chainOf(4);
        AuditChainHead head = headFor(entries);
        head.setEntryCount(9);

        AuditChainVerifier.ChainVerification result = verifierOver(entries, head).verify();

        assertThat(result.intact()).isFalse();
        assertThat(result.problem()).contains("4 entries but 9");
    }

    // ---------- paging ----------

    @Test
    void verifiesAChainLongerThanOnePage() {
        List<AuditLog> entries = chainOf(1201);

        AuditChainVerifier.ChainVerification result = verify(entries);

        assertThat(result.intact()).isTrue();
        assertThat(result.checkedEntries()).isEqualTo(1201);
    }

    @Test
    void findsABreakBeyondTheFirstSlice() {
        List<AuditLog> entries = chainOf(1201);
        AuditChainHead head = headFor(entries);
        AuditLog victim = entries.get(900);
        victim.setIp("127.0.0.1");

        AuditChainVerifier.ChainVerification result = verifierOver(entries, head).verify();

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenEntryId()).isEqualTo(victim.getId());
    }

    /**
     * Verification does not block writers, so entries can land while the walk is in
     * progress. Those sit beyond the count the head recorded when the walk started
     * and must be left for the next run — not reported as tampering. Reading the
     * head after the walk instead of before made every concurrent audit write look
     * like entries deleted from the end, and opening any patient record writes one.
     */
    @Test
    void ignoresEntriesAppendedWhileTheWalkIsRunning() {
        List<AuditLog> entries = chainOf(5);
        AuditChainHead headAtStart = headFor(entries);

        List<AuditLog> withLateArrivals = chainOf(8);

        AuditChainVerifier.ChainVerification result =
                verifierOver(withLateArrivals, headAtStart).verify();

        assertThat(result.intact()).isTrue();
        assertThat(result.checkedEntries()).isEqualTo(5);
        assertThat(result.headHash()).isEqualTo(entries.get(4).getEntryHash());
    }

    // ---------- entries written before the chain existed ----------

    @Test
    void reportsPreMigrationEntriesSeparatelyInsteadOfClaimingThemVerified() {
        List<AuditLog> entries = chainOf(3);
        AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
        when(logs.countByEntryHashIsNull()).thenReturn(12L);
        when(logs.findByEntryHashIsNotNullAndIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenAnswer(invocation -> sliceOf(
                        entries, invocation.getArgument(0), invocation.getArgument(1)));

        AuditChainHeadRepository heads = Mockito.mock(AuditChainHeadRepository.class);
        when(heads.peekHead()).thenReturn(Optional.of(headFor(entries)));

        AuditChainVerifier.ChainVerification result = new AuditChainVerifier(logs, heads).verify();

        assertThat(result.intact()).isTrue();
        assertThat(result.checkedEntries()).isEqualTo(3);
        assertThat(result.unchainedEntries()).isEqualTo(12);
    }
}
