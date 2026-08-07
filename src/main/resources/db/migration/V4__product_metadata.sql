-- V4__product_metadata.sql
-- Lightweight taxonomy + filters. All columns are nullable so existing
-- rows and CSVs stay valid. New fields:
--   * product.category     : free text, e.g. "Pizza", "Soup", "Drink"
--   * product.tags         : comma-separated, e.g. "vegetarian,spicy,gluten-free"
--   * product.weight_grams : integer, for macros per 100g and sorting
--   * menu_item.spice_level: 0 (none) .. 3 (very hot), NULL = unknown
--
-- Tags are validated at the application layer against an allowlist
-- (lettuce-leaf, frozen, etc.) so a typo cannot pollute the index.

ALTER TABLE product
    ADD COLUMN category      VARCHAR(60),
    ADD COLUMN tags          VARCHAR(250),
    ADD COLUMN weight_grams  INT;

ALTER TABLE menu_item
    ADD COLUMN spice_level   INT;

ALTER TABLE product
    ADD CONSTRAINT ck_product_weight_grams
        CHECK (weight_grams IS NULL OR (weight_grams > 0 AND weight_grams <= 100000));

ALTER TABLE menu_item
    ADD CONSTRAINT ck_menu_item_spice_level
        CHECK (spice_level IS NULL OR (spice_level >= 0 AND spice_level <= 3));

CREATE INDEX ix_product_category ON product (restaurant_id, category)
    WHERE category IS NOT NULL;
