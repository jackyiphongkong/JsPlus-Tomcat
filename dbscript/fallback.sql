-- ============================================================================
-- JsPlus-Tomcat database reset script (fallback)
-- Drops every application table so the schema can be recreated cleanly by
-- change.sql. DROP TABLE IF EXISTS keeps this script re-runnable on both a
-- fresh database (first-time CI) and an existing one (local reset).
-- Run order by the launcher: fallback.sql -> change.sql.
-- ============================================================================

DROP TABLE IF EXISTS ghost_remote_command;
DROP TABLE IF EXISTS domain_auth;
DROP TABLE IF EXISTS domain_auth_user;
DROP TABLE IF EXISTS domain_mapping_param;
DROP TABLE IF EXISTS domain_ssl_host;
DROP TABLE IF EXISTS domain_ip_rule;
DROP TABLE IF EXISTS ip_request_log;
DROP TABLE IF EXISTS ip_defense_rule;
DROP TABLE IF EXISTS ip_ban;
