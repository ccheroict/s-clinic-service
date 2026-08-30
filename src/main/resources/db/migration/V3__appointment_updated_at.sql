-- V3: Add updated_at column and performance indexes for appointment table
ALTER TABLE appointment ADD COLUMN updated_at timestamptz;

-- Backfill existing rows
UPDATE appointment SET updated_at = created_at WHERE updated_at IS NULL;

-- Add indexes for filtering performance.
--
-- NOTE: this previously read `CREATE INDEX ... ON appointment ((scheduled_at::date))`,
-- which PostgreSQL rejects with "functions in index expression must be marked
-- IMMUTABLE": casting timestamptz to date depends on the session TimeZone, so the
-- expression is STABLE. That made V3 fail on every clean database, aborting the
-- whole migration chain.
--
-- A plain btree index on scheduled_at is the correct replacement: every date filter
-- in AppointmentRepository.findWithFilters is a half-open range
-- (scheduled_at >= :from AND scheduled_at < :to), which a btree index serves directly.
-- No query casts scheduled_at to date.
CREATE INDEX idx_appt_scheduled_at ON appointment (scheduled_at);
CREATE INDEX idx_appt_status ON appointment (status);
CREATE INDEX idx_appt_patient_time ON appointment (patient_id, scheduled_at);
