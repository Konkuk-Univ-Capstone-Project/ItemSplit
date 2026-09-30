-- Read-only audit after 001_common_contract.sql; review every returned row.
-- No automatic price/total/assignee correction is performed.
SELECT id, receipt_id, price, quantity FROM items
WHERE price NOT BETWEEN 1 AND 1000000 OR quantity NOT BETWEEN 1 AND 999
   OR price::NUMERIC * quantity > 10000000;
SELECT id, declared_total FROM receipts
WHERE declared_total IS NOT NULL AND declared_total NOT BETWEEN 1 AND 10000000;
SELECT r.id, r.declared_total, COALESCE(SUM(i.price::NUMERIC * i.quantity), 0) AS item_total
FROM receipts r LEFT JOIN items i ON i.receipt_id = r.id
GROUP BY r.id, r.declared_total
HAVING r.declared_total IS NULL OR r.declared_total <> COALESCE(SUM(i.price::NUMERIC * i.quantity), 0)
    OR COALESCE(SUM(i.price::NUMERIC * i.quantity), 0) > 10000000;
SELECT r.room_id, SUM(i.price::NUMERIC * i.quantity) AS room_total
FROM receipts r JOIN items i ON i.receipt_id = r.id
GROUP BY r.room_id HAVING SUM(i.price::NUMERIC * i.quantity) > 100000000;
SELECT i.id AS item_id, r.id AS receipt_id, r.payer_member_id
FROM items i JOIN receipts r ON r.id = i.receipt_id
WHERE NOT i.excluded_from_settlement
  AND (r.payer_member_id IS NULL OR NOT EXISTS (SELECT 1 FROM assignments a WHERE a.item_id = i.id));
SELECT a.id AS assignment_id, i.id AS item_id
FROM assignments a JOIN items i ON i.id = a.item_id
JOIN receipts r ON r.id = i.receipt_id JOIN room_members m ON m.id = a.room_member_id
WHERE m.room_id <> r.room_id OR i.excluded_from_settlement;
SELECT r.id AS receipt_id FROM receipts r JOIN room_members m ON m.id = r.payer_member_id
WHERE m.room_id <> r.room_id;
-- Once invalid legacy values have been corrected, validate checks separately:
-- ALTER TABLE items VALIDATE CONSTRAINT ck_items_money_contract;
-- ALTER TABLE receipts VALIDATE CONSTRAINT ck_receipts_declared_total_contract;
