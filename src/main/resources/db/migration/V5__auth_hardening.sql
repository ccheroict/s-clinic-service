-- ============================================================
-- V5: Replace HTTP Basic with server-side session tokens, and add the
--     account-protection state the previous design had no place for.
--
-- Why opaque server-side tokens rather than JWT: revoking access to medical
-- records must take effect immediately. A stateless JWT stays valid until it
-- expires, which is unacceptable when a staff member is dismissed or a device
-- is lost.
-- ============================================================

-- ---------- Session tokens ----------
create table session_token (
    id           uuid primary key default gen_random_uuid(),
    staff_id     uuid not null references staff(id),

    -- SHA-256 of the raw token. The raw value is shown to the client once and
    -- never stored, so a database leak does not hand over live sessions.
    token_hash   text not null unique,

    -- FULL grants access to business endpoints. The others are intermediate
    -- states that only the auth endpoints accept (change password, MFA steps).
    scope        text not null
                 check (scope in ('FULL','CHANGE_PASSWORD','MFA_PENDING','ENROLL_MFA')),

    issued_at    timestamptz not null default now(),
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    last_used_at timestamptz,
    ip           text,
    user_agent   text
);
create index idx_session_token_staff   on session_token (staff_id);
create index idx_session_token_expires on session_token (expires_at);

-- ---------- Authentication event log ----------
-- Separate from audit_log: these events are about access attempts (including
-- failures, where no staff row may resolve at all), not about record changes.
create table auth_event (
    id         bigserial primary key,
    username   text not null,               -- as supplied, may not match any staff
    staff_id   uuid references staff(id),   -- null when the username is unknown
    event_type text not null,
    succeeded  boolean not null,
    ip         text,
    user_agent text,
    detail     text,
    created_at timestamptz not null default now()
);
create index idx_auth_event_username on auth_event (username, created_at desc);
create index idx_auth_event_staff    on auth_event (staff_id, created_at desc);
create index idx_auth_event_type     on auth_event (event_type, created_at desc);

-- ---------- Password history ----------
-- Enforces "cannot reuse a recent password" without keeping the passwords
-- themselves; only bcrypt hashes are retained.
create table staff_password_history (
    id            bigserial primary key,
    staff_id      uuid not null references staff(id) on delete cascade,
    password_hash text not null,
    created_at    timestamptz not null default now()
);
create index idx_password_history_staff on staff_password_history (staff_id, created_at desc);

-- ---------- Account protection state on staff ----------
alter table staff
    add column password_changed_at  timestamptz,
    add column must_change_password boolean not null default false,
    add column failed_attempts      int not null default 0,
    add column locked_until         timestamptz;

-- Existing accounts predate the password policy, so require a change on next login.
update staff set must_change_password = true where password_changed_at is null;
