-- V7__origin_country_varchar.sql
-- Repair: V6 declared product_ingredient.origin_country as CHAR(2) but
-- the JPA entity uses String (Hibernate maps to VARCHAR by default).
-- CHAR pads with spaces which would force a TRIM on every read; VARCHAR
-- is the more natural type for ISO 3166-1 alpha-2 codes anyway (always
-- exactly 2 chars, no padding benefit). The application validates
-- length == 2 on the way in, so there's no constraint regression.
ALTER TABLE product_ingredient
    ALTER COLUMN origin_country TYPE VARCHAR(2);
