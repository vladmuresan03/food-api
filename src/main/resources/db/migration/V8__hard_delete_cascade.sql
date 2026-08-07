-- V8__hard_delete_cascade.sql
-- Enable true (cascading) DELETE on parent entities (restaurant, menu,
-- product). Before this migration, the soft "archive" status was the
-- only viable delete path because PostgreSQL's default FK action is
-- NO ACTION, which blocks DELETE whenever any child row exists.
--
-- Why we want true DELETE:
--   * GDPR right-to-be-forgotten: a restaurant that fully exits the
--     platform must not leave a ghost in the catalog.
--   * Accidental import cleanup: a misfired bundle that imported 200
--     wrong rows should be reversible, not archived.
--   * Bundle re-runs: a re-import that overwrites a row should not
--     pile ARCHIVED duplicates in the DB.
--
-- Constraint names below were introspected from the live DB
-- (Spring Data JPA's @RepositoryMethodMetadataPostProcessor was
-- not used; see src/test/java/com/foodfinder/schema/DumpFksTest
-- which was a one-off introspection helper, deleted after use).
--
-- product_nutrition and product_ingredient already had
-- ON DELETE CASCADE on their product_id FKs (V6), so the natural-key
-- invariants from the menu_item / photo composites do the rest: when
-- we delete a product, the composite (restaurant_id, product_id) FK
-- on menu_item and photo fires, and the CASCADE on nutrition +
-- ingredients fires. When we delete a menu, menu_asset cascades,
-- and menu_item (composite to menu) cascades. When we delete a
-- restaurant, EVERYTHING cascades.

ALTER TABLE menu
    DROP CONSTRAINT menu_restaurant_id_fkey,
    ADD  CONSTRAINT menu_restaurant_id_fkey
         FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE;

ALTER TABLE product
    DROP CONSTRAINT product_restaurant_id_fkey,
    ADD  CONSTRAINT product_restaurant_id_fkey
         FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE;

ALTER TABLE menu_item
    DROP CONSTRAINT fk_menu_item_menu,
    ADD  CONSTRAINT fk_menu_item_menu
         FOREIGN KEY (restaurant_id, menu_id)
         REFERENCES menu (restaurant_id, id) ON DELETE CASCADE;

ALTER TABLE menu_item
    DROP CONSTRAINT fk_menu_item_product,
    ADD  CONSTRAINT fk_menu_item_product
         FOREIGN KEY (restaurant_id, product_id)
         REFERENCES product (restaurant_id, id) ON DELETE CASCADE;

ALTER TABLE photo
    DROP CONSTRAINT photo_restaurant_id_fkey,
    ADD  CONSTRAINT photo_restaurant_id_fkey
         FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE CASCADE;

ALTER TABLE photo
    DROP CONSTRAINT fk_photo_product,
    ADD  CONSTRAINT fk_photo_product
         FOREIGN KEY (restaurant_id, product_id)
         REFERENCES product (restaurant_id, id) ON DELETE CASCADE;

ALTER TABLE menu_asset
    DROP CONSTRAINT menu_asset_menu_id_fkey,
    ADD  CONSTRAINT menu_asset_menu_id_fkey
         FOREIGN KEY (menu_id) REFERENCES menu (id) ON DELETE CASCADE;
