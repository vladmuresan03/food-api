-- V9__csv_log_slug_tier1a.sql
-- Extend csv_import_log.slug check constraint to include the two new
-- Tier 1A resources. PostgreSQL cannot ALTER a CHECK constraint in
-- place, so the pattern is DROP + ADD. The same constraint is in V2.

ALTER TABLE csv_import_log DROP CONSTRAINT ck_csv_import_log_slug;

ALTER TABLE csv_import_log ADD CONSTRAINT ck_csv_import_log_slug
    CHECK (slug IN ('restaurants','menus','products','nutrition','ingredients',
                    'menu-items','photos','menu-assets','bundle'));
