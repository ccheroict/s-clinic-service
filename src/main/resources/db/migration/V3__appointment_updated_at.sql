-- V3: Add updated_at column and performance indexes for appointment table
ALTER TABLE appointment ADD COLUMN updated_at timestamptz;

-- Backfill existing rows
UPDATE appointment SET updated_at = created_at WHERE updated_at IS NULL;

-- Add indexes for filtering performance
CREATE INDEX idx_appt_scheduled_date ON appointment ((scheduled_at::date));
CREATE INDEX idx_appt_status ON appointment (status);
CREATE INDEX idx_appt_patient_time ON appointment (patient_id, scheduled_at);
