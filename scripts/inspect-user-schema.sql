-- Read-only schema inventory. Connect explicitly to the intended user database first.
-- No user rows, password hashes, refresh tokens, or credentials are selected.
-- Save this output together with SHOW CREATE TABLE for the four user tables.

SELECT DATABASE() AS inspected_database, VERSION() AS database_version,
       @@lower_case_table_names AS lower_case_table_names,
       @@character_set_database AS default_character_set,
       @@collation_database AS default_collation, @@sql_mode AS sql_mode;

SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
       COLUMN_DEFAULT, EXTRA, CHARACTER_SET_NAME, COLLATION_NAME,
       DATETIME_PRECISION, GENERATION_EXPRESSION
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND LOWER(TABLE_NAME) IN ('users', 'user_auth', 'local_credential', 'refresh_token')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, SUB_PART, INDEX_TYPE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND LOWER(TABLE_NAME) IN ('users', 'user_auth', 'local_credential', 'refresh_token')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME, k.ORDINAL_POSITION,
       k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME,
       r.UPDATE_RULE, r.DELETE_RULE
FROM information_schema.KEY_COLUMN_USAGE k
JOIN information_schema.REFERENTIAL_CONSTRAINTS r
  ON k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA
 AND k.TABLE_NAME = r.TABLE_NAME AND k.CONSTRAINT_NAME = r.CONSTRAINT_NAME
WHERE k.TABLE_SCHEMA = DATABASE()
  AND LOWER(k.TABLE_NAME) IN ('users', 'user_auth', 'local_credential', 'refresh_token')
ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION;

SELECT t.TABLE_NAME, c.CONSTRAINT_NAME, c.CHECK_CLAUSE
FROM information_schema.TABLE_CONSTRAINTS t
JOIN information_schema.CHECK_CONSTRAINTS c
  ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME
WHERE t.TABLE_SCHEMA = DATABASE() AND t.CONSTRAINT_TYPE = 'CHECK'
  AND LOWER(t.TABLE_NAME) IN ('users', 'user_auth', 'local_credential', 'refresh_token')
ORDER BY t.TABLE_NAME, c.CONSTRAINT_NAME;

SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, EVENT_MANIPULATION, ACTION_TIMING, ACTION_STATEMENT
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND LOWER(EVENT_OBJECT_TABLE) IN ('users', 'user_auth', 'local_credential', 'refresh_token')
ORDER BY EVENT_OBJECT_TABLE, TRIGGER_NAME;

-- If actual table names differ in case, use the exact names reported above.
-- Run these individually after confirming all four tables exist:
-- SHOW CREATE TABLE users;
-- SHOW CREATE TABLE user_auth;
-- SHOW CREATE TABLE local_credential;
-- SHOW CREATE TABLE refresh_token;
-- If flyway_schema_history exists, export its history separately and review before baseline.
