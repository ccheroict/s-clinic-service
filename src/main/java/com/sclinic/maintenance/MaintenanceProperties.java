package com.sclinic.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long the security housekeeping tables are kept.
 *
 * <p>Both durations are <b>unset by default, which means keep everything</b>.
 * That is not laziness; it is the only safe default for these two tables, and
 * the reasons differ.
 *
 * @param sessionTokenRetention how long to keep expired session rows.
 *        <p>Unset means never delete them. {@code audit_log.session_id} points
 *        at these rows, so deleting one makes every audit entry written during
 *        that session unresolvable: the entry survives, but which device and
 *        which address it came from no longer does. Medical audit trails are
 *        kept for years, and an audit entry that can no longer say where it came
 *        from is a weaker record than one that can. Anyone setting this is
 *        accepting that trade for the sessions older than the period, and should
 *        set it well beyond the audit retention rather than below it.
 * @param authEventRetention how long to keep authentication events.
 *        <p>Unset means never delete them. This table <em>is</em> the security
 *        record of who tried to log in and when; a job that trims it is a job
 *        that erases evidence, and the evidence is most wanted long after the
 *        period in which anyone thought to ask. Vietnamese information-security
 *        practice and NĐ 13/2023 both push towards keeping access records rather
 *        than pruning them, so the number has to come from the clinic with a
 *        reason attached, exactly like the clinical retention policy.
 *
 * <p>Password history is pruned regardless, and needs no duration: the policy
 * already says how many previous passwords may not be reused, and rows past that
 * count cannot affect any decision. Pruning by count rather than by age means no
 * number has to be invented.
 */
@ConfigurationProperties(prefix = "sclinic.maintenance")
public record MaintenanceProperties(
        Duration sessionTokenRetention,
        Duration authEventRetention
) {

    public boolean prunesSessionTokens() {
        return sessionTokenRetention != null && !sessionTokenRetention.isZero()
                && !sessionTokenRetention.isNegative();
    }

    public boolean prunesAuthEvents() {
        return authEventRetention != null && !authEventRetention.isZero()
                && !authEventRetention.isNegative();
    }
}
