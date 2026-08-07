-- V6__product_nutrition_and_ingredient.sql
-- Tier 1A product metadata: structured nutrition + ingredients.
-- The legal driver is EU Regulation 1169/2011 (Annex XIV mandatory
-- nutrition declaration; Art. 18 + Anex II 14 allergens; Anex VIII
-- quantitative declaration of characterizing ingredients) plus
-- Romanian ANPC enforcement on digital menus.
--
-- The two tables below are what makes FoodFinder structurally
-- different from Wolt/Glovo/Tazz, who still store ingredients as
-- a flat comma-separated string and cannot bold the allergens,
-- compute is_vegan/is_gluten_free reliably, or surface last-verified
-- metadata. The 14-allergen allowlist, Q&AI percentage, and
-- ISO 3166-1 origin are the structural wins.

-- ------------------------------------------------------------------ nutrition (1:1)

CREATE TABLE product_nutrition (
    product_id        BIGINT          PRIMARY KEY REFERENCES product (id) ON DELETE CASCADE,

    -- The 7 mandatory fields from EU 1169/2011 Annex XIV, per 100g
    -- (or 100ml, or per portion). Energy is kcal only; the kJ value
    -- is derivable in the consumer app and not needed on the wire.
    basis              VARCHAR(10)     NOT NULL DEFAULT 'per_100g',
    energy_kcal        NUMERIC(7,2),
    fat_g              NUMERIC(6,2),
    sat_fat_g          NUMERIC(6,2),
    carbs_g            NUMERIC(6,2),
    sugars_g           NUMERIC(6,2),
    protein_g          NUMERIC(6,2),
    salt_g             NUMERIC(6,3),
    -- Optional but heavily requested: fibre, used as a top filter.
    fiber_g            NUMERIC(6,2),

    -- Trust signals: where the numbers came from, and when they were
    -- last reverified. Used by the admin UI to flag stale data.
    source_url         VARCHAR(500),
    last_verified_at   TIMESTAMPTZ,

    -- Same audit columns as the rest of the catalog (Timestamped).
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_by         VARCHAR(120),

    CONSTRAINT ck_product_nutrition_basis
        CHECK (basis IN ('per_100g', 'per_100ml', 'per_portion')),
    CONSTRAINT ck_product_nutrition_energy
        CHECK (energy_kcal IS NULL OR energy_kcal >= 0),
    CONSTRAINT ck_product_nutrition_salt_3dp
        CHECK (salt_g IS NULL OR (salt_g >= 0 AND salt_g <= 100))
);

-- ------------------------------------------------------------------ ingredients (1:N, ordered)

CREATE TABLE product_ingredient (
    product_id        BIGINT          NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    position          SMALLINT        NOT NULL,

    -- Free text, e.g. "Mozzarella fior di latte", "Wheat flour type 0".
    -- No FK to a separate ingredients table: the catalog is the source
    -- of truth, not a normalized taxonomy.
    name              VARCHAR(200)    NOT NULL,

    -- One of the 14 EU Annex II codes (allowlist enforced in Java):
    -- gluten, crustaceans, eggs, fish, peanuts, soybeans, milk, nuts,
    -- celery, mustard, sesame, sulphites, lupin, molluscs. Free text so
    -- the list can grow without a migration. is_allergen is a denormalized
    -- mirror of "this row has a non-null allergen_code" for index-only
    -- reads and JSON shaping.
    is_allergen       BOOLEAN         NOT NULL DEFAULT false,
    allergen_code     VARCHAR(20),

    -- Quantitative declaration of characterizing ingredients (Q&AI),
    -- EU 1169/2011 Anex VIII. Required when the ingredient is in the
    -- product name or emphasized on the label (e.g. "Mozzarella 40%").
    -- Nullable: most rows won't have a Q&AI value.
    percentage        NUMERIC(5,2),

    -- ISO 3166-1 alpha-2, e.g. "RO", "IT". Used for the "Produs local"
    -- filter and trust signals.
    origin_country    CHAR(2),

    PRIMARY KEY (product_id, position),

    CONSTRAINT ck_product_ingredient_position
        CHECK (position > 0),
    CONSTRAINT ck_product_ingredient_allergen
        CHECK (
            (is_allergen = false AND allergen_code IS NULL)
            OR (is_allergen = true AND allergen_code IS NOT NULL)
        ),
    CONSTRAINT ck_product_ingredient_percentage
        CHECK (percentage IS NULL OR (percentage >= 0 AND percentage <= 100))
);

-- Partial index: only allergen-tagged rows are interesting for
-- "show me everything without gluten / milk / peanuts" queries.
CREATE INDEX ix_product_ingredient_allergen ON product_ingredient (allergen_code)
    WHERE allergen_code IS NOT NULL;

-- ------------------------------------------------------------------ docs

COMMENT ON TABLE product_nutrition IS
    'Per-product nutrition facts (EU 1169/2011 Anex XIV). 1:1 with product; '
    'null values mean the field is not declared (consumer UI shows "?").';
COMMENT ON TABLE product_ingredient IS
    'Ordered (by position) ingredients for a product. allergens one of the 14 '
    'EU Anex II codes; percentage is the Q&AI per Anex VIII; origin_country is '
    'ISO 3166-1 alpha-2.';
