\set ON_ERROR_STOP on
BEGIN;
SELECT EXISTS(SELECT 1 FROM pg_proc WHERE proname='erp_complete_production') AS installed \gset
\if :installed
\else
\ir ../src/main/resources/inventory-schema.sql
\endif
\ir test-inventory.sql
ROLLBACK;
