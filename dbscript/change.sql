-- ============================================================================
-- JsPlus-Tomcat database initialization script (English-annotated)
-- Main DB: tomcatdb (jdbc:h2:tcp://localhost:{H2_PORT}/tomcatdb)
-- An equivalent Traditional-Chinese annotated copy is available as
-- change_zh_hk.sql (same DDL, comments in 繁體中文).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Application DB account (created if not exists)
-- sample_user / sample_password are the shipped sample credentials.
-- ----------------------------------------------------------------------------
CREATE USER IF NOT EXISTS sample_user PASSWORD 'sample_password';

-- Grant all privileges on PUBLIC schema to sample_user
GRANT ALL ON SCHEMA PUBLIC TO sample_user;
-- Promote sample_user to ADMIN
ALTER USER sample_user ADMIN TRUE;

-- ============================================================================
-- Table 1: ghost_remote_command (ghost remote command queue)
-- Purpose: holds remote command rows for ghost consoles (headless runtimes),
--          used for cross-process / remote control.
-- NOTE: this table uses plain CREATE TABLE (no IF NOT EXISTS).
-- ============================================================================
CREATE TABLE ghost_remote_command (
    id CHAR(16) PRIMARY KEY,        -- PK: unique command record id
    ghost_name VARCHAR(200),        -- Ghost name: which ghost console owns the command
    request_script VARCHAR(500),    -- Script to be executed by the ghost
    response_message VARCHAR(500),  -- Text returned by the ghost after execution
    processed INT,                  -- Processed flag: 0=pending, 1=processed
    requested INT,                  -- Request counter
    responsed INT,                  -- Response counter
    created_at TIMESTAMP,           -- Creation time
    updated_at TIMESTAMP,           -- Last update time
    deleted_at TIMESTAMP,           -- Soft-delete time (NULL when active)
    deleted SMALLINT                -- Soft-delete flag: 0=active, 1=deleted
);

-- ============================================================================
-- Table 2: domain_auth (per-domain Basic Auth settings)
-- Purpose: controls whether each domain:port enables Basic Auth and how
--          passwords are encrypted. Read by SecurityFilter per request.
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_auth (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique setting id
    domain          VARCHAR(200),          -- Domain (lowercase); "0" = server-wide fallback
    port            VARCHAR(50),           -- Port; "0" = server-wide fallback
    auth_enable     BOOLEAN,               -- Auth switch: true = enable Basic Auth for this domain:port
    encrypt_mode    VARCHAR(100),          -- Encryption mode (e.g. AES)
    encrypt_key     VARCHAR(300),          -- AES encryption key
    created_at      TIMESTAMP,             -- Creation time
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 3: domain_auth_user (per-domain auth users)
-- Purpose: stores valid usernames and encrypted passwords per domain:port.
-- Logical FK: domain_auth_id -> domain_auth.id (no physical FK constraint).
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_auth_user (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique user record id
    domain_auth_id  CHAR(16),              -- FK (logical): owning domain_auth.id
    username        VARCHAR(200),          -- Username
    encrypt_pass    VARCHAR(500),          -- AES-encrypted password (never plain text)
    created_at      TIMESTAMP,             -- Creation time
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 4: domain_mapping_param (reverse-proxy domain mappings)
-- Purpose: stores the proxy target of each domain:port[/uri]; DomainProxy routes
--          requests to the backend accordingly. Loaded at startup by
--          loadDomainMapping(); setDomainMapping(...) applies immediately.
--          uri is a prefix matcher: blank = all URIs under domain:port match,
--          non-blank (e.g. "/api") matches every request path starting with it
--          (longest prefix wins when several uri mappings exist).
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_mapping_param (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique mapping id
    domain          VARCHAR(200),          -- Domain (lowercase)
    port            VARCHAR(50),           -- Port
    uri             VARCHAR(50),           -- URI prefix: blank = all URIs match
    param_value     VARCHAR(500),          -- Proxy target params (e.g. http://localhost:8081)
    created_at      TIMESTAMP,             -- Creation time
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 5: domain_ssl_host (per-domain SSL host certificates)
-- Purpose: stores additional SSL host certificate info (keystore, pass, alias).
-- addSslHost(...) hot-loads onto the running server; update/remove only change
-- the DB record and require a Tomcat restart.
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_ssl_host (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique SSL host id
    hostname        VARCHAR(200),          -- Hostname (e.g. sample.com)
    keystore_file   VARCHAR(200),          -- Keystore file name (under SSL.keyRoot)
    keystore_pass   VARCHAR(500),          -- Keystore password (AES-encrypted, per-row key)
    key_alias       VARCHAR(200),          -- Key alias
    encrypt_mode    VARCHAR(10),           -- Encryption mode of keystore_pass ("AES"); NULL = legacy plaintext
    encrypt_key     VARCHAR(500),          -- Per-row AES key used to encrypt keystore_pass
    created_at      TIMESTAMP,             -- Creation time
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 6: domain_ip_rule (IP allow/deny rules)
-- Purpose: stores per-domain:port IP access rules, enforced by IpFilter.
-- One rule per row; domain="0", port="0" is the server-wide fallback rule
-- (applies to every domain that has no rules of its own).
-- Patterns: exact IP (192.168.1.10), CIDR (192.168.1.0/24), wildcard (192.168.1.*).
-- uri: blank = the rule matches every URI on the domain:port; non-blank (e.g.
--      /api) = the rule applies only to request paths under that URI prefix
--      (longest-prefix match, same convention as DomainProxy).
-- ip_ban_id: set when the rule was auto-created by the attack-defense engine
--            (links to ip_ban.id); NULL = manually created rule.
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_ip_rule (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique rule id
    domain          VARCHAR(200),          -- Domain (lowercase); "0" = server-wide fallback
    port            VARCHAR(50),           -- Port; "0" = server-wide fallback
    uri             VARCHAR(50),           -- URI prefix: blank = every URI on the domain:port;
                                           -- non-blank = longest-prefix match only (see IpFilter)
    rule_type       VARCHAR(10),           -- Rule type: ALLOW / DENY
    ip_pattern      VARCHAR(200),          -- IP pattern: exact IP / CIDR / wildcard
    ip_ban_id       CHAR(16),              -- Origin ban id: links ip_ban.id (NULL = manual rule)
    created_at      TIMESTAMP,             -- Creation time
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 7: ip_request_log (per-IP request records)
-- Purpose: records per-request IP behaviour (domain, port, URI, method,
--          remote address, User-Agent, ...) for attack analysis.
-- Writes are asynchronous and batched: requests are queued into a CacheArray
-- and flushed by a background thread every ~1s using
-- CacheDriverExecutor.executeBatch (non-blocking for the request path).
-- ============================================================================
CREATE TABLE IF NOT EXISTS ip_request_log (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique request record id
    domain          VARCHAR(200),          -- Request domain (lowercase)
    port            VARCHAR(50),           -- Request port
    uri             VARCHAR(500),          -- Request URI (path, max 500 chars)
    method          VARCHAR(20),           -- HTTP method: GET/POST/PUT/DELETE...
    remote_addr     VARCHAR(200),          -- Client source IP
    user_agent      VARCHAR(500),          -- User-Agent header
    request_time    TIMESTAMP,             -- Request time
    created_at      TIMESTAMP,             -- Creation time (same as request_time)
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 8: ip_defense_rule (IP attack-defense rules)
-- Purpose: defines what request behaviour counts as an attack; enforced by
--          IpAttackDefense. Each enabled rule counts requests per IP within a
--          sliding window (window_seconds); reaching threshold auto-bans the
--          IP for ban_seconds.
-- Matching: a rule matches by domain:port ("0":"0" makes it server-wide).
--   uri_pattern set   -> the request URI must also match the regex (find())
--   uri_pattern blank -> only the domain:port is checked
-- ============================================================================
CREATE TABLE IF NOT EXISTS ip_defense_rule (
    id              CHAR(16) PRIMARY KEY,  -- PK: unique rule id
    rule_name       VARCHAR(100),          -- Rule name (required, for identification only)
    domain          VARCHAR(200),          -- Matched domain (lowercase); "0" = server-wide
    port            VARCHAR(50),           -- Matched port; "0" = server-wide
    uri_pattern     VARCHAR(500),          -- URI regex; blank = match any URI on the domain:port
    window_seconds  INT,                   -- Sliding window length (s): counts reset after expiry
    threshold       INT,                   -- Ban trigger: request count >= threshold
    ban_seconds     INT,                   -- Ban duration (s): sets ip_ban.expire_time
    enabled         BOOLEAN,               -- Rule switch: true = participates in defense checks
    created_at      TIMESTAMP,             -- Creation time
    updated_at      TIMESTAMP,             -- Last update time
    deleted_at      TIMESTAMP,             -- Soft-delete time (NULL when active)
    deleted         BOOLEAN                -- Soft-delete flag: false=active, true=deleted
);

-- ============================================================================
-- Table 9: ip_ban (automatic ban records)
-- Purpose: records bans issued by the defense engine (with expiry) for
--          auto-unban and manual inspection.
-- Flow: rule hit -> write DENY into domain_ip_rule (IpFilter blocks with 403)
--       -> insert this row -> background thread scans expire_time < now,
--       removes the DENY rule and soft-deletes this record.
-- ============================================================================
CREATE TABLE IF NOT EXISTS ip_ban (
    id                  CHAR(16) PRIMARY KEY,  -- PK: unique ban id
    ip_defense_rule_id  CHAR(16),              -- FK (logical): triggering ip_defense_rule.id
    domain              VARCHAR(200),          -- Domain the ban was written for ("0" = server-wide)
    port                VARCHAR(50),           -- Port the ban was written for ("0" = server-wide)
    ip                  VARCHAR(200),          -- Banned IP address
    ban_time            TIMESTAMP,             -- Ban start time
    expire_time         TIMESTAMP,             -- Ban expiry (= ban_time + ban_seconds)
    created_at          TIMESTAMP,             -- Creation time (same as ban_time)
    updated_at          TIMESTAMP,             -- Last update time
    deleted_at          TIMESTAMP,             -- Soft-delete time (set when unbanned)
    deleted             BOOLEAN                -- Soft-delete flag: false=banned, true=unbanned
);
