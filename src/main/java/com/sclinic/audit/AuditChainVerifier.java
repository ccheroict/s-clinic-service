package com.sclinic.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Walks the audit hash chain and reports whether it is intact.
 *
 * <p>Recomputes each entry's hash from the stored fields plus the previous
 * entry's hash. Any of the following breaks a link and is reported with the id
 * where it was first noticed:
 *
 * <ul>
 *   <li>a field of an entry was altered — its own hash no longer matches;</li>
 *   <li>an entry was removed from the middle — the next entry's {@code prevHash}
 *       points at a hash that is no longer the one before it;</li>
 *   <li>an entry was inserted in the middle — same as above.</li>
 * </ul>
 *
 * <p>Entries removed from the very <em>end</em> of the chain leave nothing behind
 * to notice, because nothing follows them. That is what {@link AuditChainHead} is
 * for: the expected head hash and entry count live in a separate row, so a
 * truncated tail shows up as a walk that ends somewhere other than where the head
 * row says it should.
 *
 * <p><b>What this does not prove.</b> The digest is an unkeyed SHA-256 over
 * fields that are all readable from the table, and the only reference point
 * outside {@code audit_log} is {@code audit_chain_head}, in the same database.
 * Anyone who can write to both can rewrite history and recompute a chain and head
 * that agree. This detects tampering by anything short of that — application bugs,
 * a careless migration, SQL injection, an operator editing a row — which is the
 * realistic case. Turning it into evidence against a determined administrator
 * needs a key the database does not hold, or the head hash published somewhere
 * append-only off the box.
 */
@Service
@RequiredArgsConstructor
public class AuditChainVerifier {

    /** Read in slices: the trail is expected to outgrow memory. */
    private static final int SLICE_SIZE = 500;

    private final AuditLogRepository auditLogRepository;
    private final AuditChainHeadRepository chainHeadRepository;

    /**
     * Verification is read-only and does not block writers, so entries can be
     * appended while the walk is in progress. The head is therefore read
     * <em>first</em> and its entry count is treated as the target the walk must
     * reach: anything committed afterwards sits beyond that target and is left for
     * the next run. Reading the head last instead would make any concurrent audit
     * write — and every patient record opened writes one — look like entries
     * removed from the end of the chain.
     */
    @Transactional(readOnly = true)
    public ChainVerification verify() {
        AuditChainHead head = chainHeadRepository.peekHead().orElse(null);
        if (head == null) {
            return ChainVerification.broken(0, 0, null,
                    "the chain head record is missing, so the end of the chain cannot be confirmed");
        }

        long expectedCount = head.getEntryCount();
        String expectedHead = head.getHeadHash();
        long unchained = auditLogRepository.countByEntryHashIsNull();

        String expectedPrevHash = null;
        long checked = 0;
        long afterId = 0;

        while (checked < expectedCount) {
            List<AuditLog> slice = auditLogRepository
                    .findByEntryHashIsNotNullAndIdGreaterThanOrderByIdAsc(afterId, Limit.of(SLICE_SIZE));
            if (slice.isEmpty()) {
                break;
            }

            for (AuditLog entry : slice) {
                if (checked == expectedCount) {
                    break;
                }
                ChainVerification broken = checkLink(entry, expectedPrevHash, checked, unchained);
                if (broken != null) {
                    return broken;
                }
                expectedPrevHash = entry.getEntryHash();
                afterId = entry.getId();
                checked++;
            }
        }

        return compareWithRecordedHead(expectedPrevHash, checked, expectedCount, expectedHead, unchained);
    }

    /** @return a failed verification, or null when this link holds */
    private ChainVerification checkLink(AuditLog entry, String expectedPrevHash,
                                        long checked, long unchained) {
        if (!Objects.equals(entry.getPrevHash(), expectedPrevHash)) {
            return ChainVerification.broken(checked, unchained, entry.getId(),
                    "entry does not follow the previous one; an entry was removed or inserted");
        }

        String recomputed = AuditHash.of(expectedPrevHash, entry);
        if (!recomputed.equals(entry.getEntryHash())) {
            return ChainVerification.broken(checked, unchained, entry.getId(),
                    "entry contents do not match its recorded hash; the entry was altered");
        }
        return null;
    }

    /**
     * The links all held, but the chain still has to reach the entry the head row
     * says it ends at. This is what catches entries deleted from the end, where
     * there is no following entry to break.
     */
    private ChainVerification compareWithRecordedHead(String actualHead, long checked,
                                                     long expectedCount, String expectedHead,
                                                     long unchained) {
        if (checked != expectedCount) {
            return ChainVerification.broken(checked, unchained, null,
                    "the chain holds " + checked + " entries but " + expectedCount
                            + " were recorded; entries were removed from the end");
        }
        if (!Objects.equals(expectedHead, actualHead)) {
            return ChainVerification.broken(checked, unchained, null,
                    "the chain ends at a different entry than recorded; entries were removed from the end");
        }

        return ChainVerification.intact(checked, unchained, actualHead);
    }

    /**
     * @param intact             whether every checked link held and the chain
     *                           reaches the entry it was recorded to end at
     * @param checkedEntries     how many chained entries were verified
     * @param unchainedEntries   entries written before the chain existed, which
     *                           cannot be proven either way
     * @param headHash           hash of the last entry, null when broken
     * @param firstBrokenEntryId id where verification stopped; null when intact,
     *                           and also null when the break is at the end of the
     *                           chain, where no surviving entry carries the fault
     * @param problem            what was wrong, null when intact
     */
    public record ChainVerification(
            boolean intact,
            long checkedEntries,
            long unchainedEntries,
            String headHash,
            Long firstBrokenEntryId,
            String problem
    ) {

        static ChainVerification intact(long checked, long unchained, String headHash) {
            return new ChainVerification(true, checked, unchained, headHash, null, null);
        }

        static ChainVerification broken(long checked, long unchained, Long entryId, String problem) {
            return new ChainVerification(false, checked, unchained, null, entryId, problem);
        }
    }
}
