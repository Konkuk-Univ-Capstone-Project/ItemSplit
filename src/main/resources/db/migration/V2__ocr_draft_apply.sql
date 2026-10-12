ALTER TABLE ocr_drafts ADD COLUMN applied_revision BIGINT;
ALTER TABLE ocr_drafts ADD COLUMN applied_at TIMESTAMP;
ALTER TABLE ocr_drafts ADD COLUMN applied_by_user_id BIGINT;
ALTER TABLE ocr_draft_items ALTER COLUMN image_id DROP NOT NULL;
ALTER TABLE ocr_job_images ADD COLUMN manually_covered BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE ocr_draft_item_members (
    draft_item_id BIGINT NOT NULL REFERENCES ocr_draft_items(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES room_members(id) ON DELETE CASCADE,
    PRIMARY KEY (draft_item_id, member_id)
);
ALTER TABLE items ADD COLUMN ocr_draft_item_id BIGINT UNIQUE REFERENCES ocr_draft_items(id) ON DELETE SET NULL;
