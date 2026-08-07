-- V3__updated_by.sql
-- Best-effort audit column: principal name of whoever last wrote
-- the row, or NULL for rows inserted by CSV / bulk paths that
-- don't record a per-row actor. Indexed lightly so /admin views
-- can group by author if needed.

ALTER TABLE restaurant      ADD COLUMN updated_by VARCHAR(120);
ALTER TABLE menu            ADD COLUMN updated_by VARCHAR(120);
ALTER TABLE product         ADD COLUMN updated_by VARCHAR(120);
ALTER TABLE menu_item       ADD COLUMN updated_by VARCHAR(120);
ALTER TABLE photo           ADD COLUMN updated_by VARCHAR(120);
ALTER TABLE menu_asset      ADD COLUMN updated_by VARCHAR(120);
