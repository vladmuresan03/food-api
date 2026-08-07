-- V5__spice_level_int.sql
-- Repair: V4 declared menu_item.spice_level as SMALLINT, but the JPA entity
-- uses Integer (Hibernate maps to JDBC INTEGER / PG int4). With
-- ddl-auto=validate the running app refuses to start on prod
-- (it accepted it on the reused Testcontainers DB because the column was
-- already int4 there from a prior run). The data fits comfortably in int4,
-- so widening is safe and avoids a columnDefinition on the entity.
ALTER TABLE menu_item ALTER COLUMN spice_level TYPE INTEGER;
