-- ============================================================
-- V15: Stop a TOTP code being used twice.
--
-- A code was accepted for any step inside the drift window, every time it was
-- presented. The window is one step either side, so a six-digit code stayed
-- valid for about ninety seconds and could be replayed for all of it. Anyone who
-- saw it once — over a shoulder, in a phishing form, in a proxy log — could use
-- it again, and the second use looked exactly like the first.
--
-- RFC 6238 section 5.2 is explicit that a verifier must not accept a code it has
-- already accepted. This is the state that makes that possible: the last time
-- step this account successfully used.
--
-- A single column rather than a table of used codes. The only question ever
-- asked is whether a step is newer than the last accepted one, and steps only
-- move forward, so one number answers it. A table would also have to be pruned,
-- and an unpruned table of authentication attempts is a growing record of
-- exactly when each member of staff was at work.
-- ============================================================

alter table staff
    add column totp_last_step bigint;

comment on column staff.totp_last_step is
    'Time step of the last accepted TOTP code. A code whose step is not strictly '
        'greater is refused as a replay (RFC 6238 section 5.2). Null until the '
        'first successful verification.';
