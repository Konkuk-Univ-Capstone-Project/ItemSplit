-- Durable usage is not cascaded away when users delete rooms, receipts, images, or jobs.
CREATE TABLE ocr_daily_usage (
    quota_key VARCHAR(80) PRIMARY KEY,
    used_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_ocr_daily_usage_nonnegative CHECK (used_count >= 0)
);

-- Nullable date preserves old rows. New requests reserve quota before enqueueing each image.
ALTER TABLE ocr_job_images ADD COLUMN quota_date DATE;
ALTER TABLE ocr_job_images ADD COLUMN quota_reserved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ocr_job_images ADD COLUMN quota_invoked BOOLEAN NOT NULL DEFAULT FALSE;
