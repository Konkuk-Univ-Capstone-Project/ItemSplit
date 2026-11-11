-- An assignment row always names the room member who owes for the item.
-- An item with no participants yet has no assignment rows, so unassigned drafts stay valid.
-- Rows that cannot be tied to a member are not filled in or deleted here: the migration stops
-- and leaves the schema at V2 until they are repaired by hand.
DO $$
DECLARE
    missing_members BIGINT;
    other_room_members BIGINT;
BEGIN
    SELECT count(*) INTO missing_members
    FROM assignments
    WHERE room_member_id IS NULL;

    SELECT count(*) INTO other_room_members
    FROM assignments a
    JOIN items i ON i.id = a.item_id
    JOIN receipts r ON r.id = i.receipt_id
    JOIN room_members m ON m.id = a.room_member_id
    WHERE m.room_id <> r.room_id;

    IF missing_members > 0 OR other_room_members > 0 THEN
        RAISE EXCEPTION 'V3 stopped: % assignment(s) without a room member, % assignment(s) whose member belongs to another room. Repair them by hand (docs/database-migration-reservations.md, V3) and run the migration again.',
            missing_members, other_room_members;
    END IF;
END $$;

ALTER TABLE assignments ALTER COLUMN room_member_id SET NOT NULL;
