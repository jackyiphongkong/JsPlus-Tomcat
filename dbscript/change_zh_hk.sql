-- ============================================================================
-- JsPlus-Tomcat 資料庫初始化腳本（帶詳細注釋版）
-- 本檔案與 change.sql 之 DDL 完全等效，僅額外加入每張表及每個欄位之說明，
-- 可直接使用 H2 RunScript 或 H2 Console 執行。
-- 主資料庫：tomcatdb（jdbc:h2:tcp://localhost:{H2_PORT}/tomcatdb）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 建立應用程式資料庫帳號（若不存在）
-- sample_user / sample_password 為隨附範例帳號，可依實際環境修改
-- ----------------------------------------------------------------------------
CREATE USER IF NOT EXISTS sample_user PASSWORD 'sample_password';

-- 授予 sample_user 於 PUBLIC SCHEMA 之全部權限
GRANT ALL ON SCHEMA PUBLIC TO sample_user;
-- 將 sample_user 提升為 ADMIN（具資料庫管理員權限）
ALTER USER sample_user ADMIN TRUE;

-- ============================================================================
-- 資料表 1：ghost_remote_command（Ghost 遠端指令佇列）
-- 用途：供 ghost 主控台（無頭執行環境）存放遠端指令及回應，用於跨程序/遠端控制
-- 注意：本表為 CREATE TABLE（非 IF NOT EXISTS），與其他表不同
-- ============================================================================
CREATE TABLE ghost_remote_command (
    id CHAR(16) PRIMARY KEY,        -- 主鍵：指令記錄唯一識別碼（16 位元）
    ghost_name VARCHAR(200),        -- Ghost 名稱：識別由哪一個 ghost 主控台發出/處理
    request_script VARCHAR(500),    -- 請求腳本：ghost 需執行之腳本內容
    response_message VARCHAR(500),  -- 回應訊息：ghost 執行後回傳之結果文字
    processed INT,                  -- 已處理標記：0=未處理，1=已處理（計數型旗標）
    requested INT,                  -- 請求計數：累計請求次數
    responsed INT,                  -- 回應計數：累計回應次數
    created_at TIMESTAMP,           -- 建立時間
    updated_at TIMESTAMP,           -- 最後更新時間
    deleted_at TIMESTAMP,           -- 軟刪除時間（未刪除時為 NULL）
    deleted SMALLINT                -- 軟刪除標記：0=有效，1=已刪除（SMALLINT 型態）
);

-- ============================================================================
-- 資料表 2：domain_auth（按網域 Basic Auth 設定）
-- 用途：控制每個 domain:port 是否啟用 Basic Auth 驗證，及其加密設定
-- 由 SecurityFilter 於請求處理時讀取
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_auth (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：設定記錄唯一識別碼
    domain          VARCHAR(200),          -- 網域名稱（小寫）；"0" 表示全伺服器後備規則
    port            VARCHAR(50),           -- 連接埠；"0" 表示全伺服器後備規則
    auth_enable     BOOLEAN,               -- 驗證開關：true=此 domain:port 啟用 Basic Auth
    encrypt_mode    VARCHAR(100),          -- 加密模式：例如 AES，用於密碼加密方式
    encrypt_key     VARCHAR(300),          -- 加密金鑰：AES 加解密用之金鑰
    created_at      TIMESTAMP,             -- 建立時間
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 3：domain_auth_user（按網域驗證使用者帳號）
-- 用途：存放各 domain:port 之合法使用者帳號與加密密碼
-- 外鍵邏輯：domain_auth_id 關聯 domain_auth.id（未建立實體外鍵約束）
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_auth_user (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：使用者記錄唯一識別碼
    domain_auth_id  CHAR(16),              -- 關聯 domain_auth.id：所屬之驗證設定
    username        VARCHAR(200),          -- 使用者名稱
    encrypt_pass    VARCHAR(500),          -- 加密後之密碼（AES 加密，非明文）
    created_at      TIMESTAMP,             -- 建立時間
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 4：domain_mapping_param（反向代理網域對應）
-- 用途：記錄每個 domain:port 之代理目標參數，DomainProxy 依此將請求轉發至後端
-- 由 loadDomainMapping() 於啟動時載入代理快取；setDomainMapping 可即時生效
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_mapping_param (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：對應記錄唯一識別碼
    domain          VARCHAR(200),          -- 網域名稱（小寫）
    port            VARCHAR(50),           -- 連接埠
    uri             VARCHAR(50),           -- URI：空白 = 該網域:連接埠之所有 URI 皆匹配
    param_value     VARCHAR(500),          -- 參數值：代理目標設定（例如 http://localhost:8081）
    created_at      TIMESTAMP,             -- 建立時間
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 5：domain_ssl_host（按網域 SSL 主機憑證）
-- 用途：存放額外 SSL 主機之憑證資訊（金鑰庫檔、密碼、別名）
-- addSslHost(...) 可熱載入至執行中伺服器；update/remove 僅改 DB，需重啟生效
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_ssl_host (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：SSL 主機記錄唯一識別碼
    hostname        VARCHAR(200),          -- 主機名稱（hostname），例如 sample.com
    keystore_file   VARCHAR(200),          -- 金鑰庫檔案名稱（位於 SSL.keyRoot 目錄下）
    keystore_pass   VARCHAR(500),          -- 金鑰庫密碼（以每列獨立金鑰進行 AES 加密）
    key_alias       VARCHAR(200),          -- 金鑰別名（alias）
    encrypt_mode    VARCHAR(10),           -- keystore_pass 的加密模式（"AES"）；NULL = 舊有明文
    encrypt_key     VARCHAR(500),          -- 加密 keystore_pass 所用的每列獨立 AES 金鑰
    created_at      TIMESTAMP,             -- 建立時間
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 6：domain_ip_rule（IP 允許/拒絕規則）
-- 用途：存放每個 domain:port 之 IP 存取控制規則，由 IpFilter 執行
-- 每行一條規則；domain="0"、port="0" 為全伺服器後備規則（套用至無自有規則之網域）
-- 規則格式：精確 IP（192.168.1.10）、CIDR（192.168.1.0/24）、萬用字元（192.168.1.*）
-- ip_ban_id：自動封禁產生之規則會填寫對應 ip_ban.id（NULL = 手動建立之規則）
-- ============================================================================
CREATE TABLE IF NOT EXISTS domain_ip_rule (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：規則記錄唯一識別碼
    domain          VARCHAR(200),          -- 網域名稱（小寫）；"0"=全伺服器後備
    port            VARCHAR(50),           -- 連接埠；"0"=全伺服器後備
    uri             VARCHAR(50),           -- URI：空白 = 該網域:連接埠之所有 URI 皆匹配
    rule_type       VARCHAR(10),           -- 規則類型：ALLOW（允許）／DENY（拒絕）
    ip_pattern      VARCHAR(200),          -- IP 樣式：精確 IP／CIDR 網段／萬用字元
    ip_ban_id       CHAR(16),              -- 來源封禁記錄：關聯 ip_ban.id（NULL=手動規則）
    created_at      TIMESTAMP,             -- 建立時間
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 7：ip_request_log（IP 請求記錄）
-- 用途：記錄每個請求之 IP 行為（網域、埠、URI、方法、來源 IP、User-Agent 等）
-- 寫入方式：請求先入 CacheArray 內存佇列，背景線程每 1 秒以
--          CacheDriverExecutor.executeBatch 批次寫入（非同步、不阻塞請求）
-- ============================================================================
CREATE TABLE IF NOT EXISTS ip_request_log (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：請求記錄唯一識別碼
    domain          VARCHAR(200),          -- 請求之網域名稱（小寫）
    port            VARCHAR(50),           -- 請求之連接埠
    uri             VARCHAR(500),          -- 請求 URI（路徑，最長 500 字元）
    method          VARCHAR(20),           -- HTTP 方法：GET/POST/PUT/DELETE 等
    remote_addr     VARCHAR(200),          -- 來源 IP 地址（用戶端）
    user_agent      VARCHAR(500),          -- User-Agent 標頭（用戶端程式識別）
    request_time    TIMESTAMP,             -- 請求發生時間
    created_at      TIMESTAMP,             -- 建立時間（與 request_time 相同）
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 8：ip_defense_rule（IP 攻擊防禦規則）
-- 用途：定義「何種請求行為視為攻擊」之規則，由 IpAttackDefense 執行
-- 每條啟用規則對命中 IP 於滑動時間窗（window_seconds）內計數，
-- 達到 threshold 即自動封禁 ban_seconds 秒
-- 匹配規則：按 domain:port 匹配（"0":"0" 即為全伺服器全域）
--   uri_pattern 有值  -> 請求 URI 亦須符合正則（find()）
--   uri_pattern 空白  -> 只檢查 domain:port
-- ============================================================================
CREATE TABLE IF NOT EXISTS ip_defense_rule (
    id              CHAR(16) PRIMARY KEY,  -- 主鍵：規則記錄唯一識別碼
    rule_name       VARCHAR(100),          -- 規則名稱（辨識用途，建立時必填）
    domain          VARCHAR(200),          -- 匹配網域（小寫）；"0" = 全域
    port            VARCHAR(50),           -- 匹配連接埠；"0" = 全域
    uri_pattern     VARCHAR(500),          -- URI 正則；空白 = 該網域:連接埠之所有 URI 皆匹配
    window_seconds  INT,                   -- 滑動時間窗長度（秒）：窗口內計數，過期歸零
    threshold       INT,                   -- 觸發封禁之請求次數門檻（count >= threshold）
    ban_seconds     INT,                   -- 封禁時長（秒）：決定 ip_ban.expire_time
    enabled         BOOLEAN,               -- 規則開關：true=參與防禦檢查
    created_at      TIMESTAMP,             -- 建立時間
    updated_at      TIMESTAMP,             -- 最後更新時間
    deleted_at      TIMESTAMP,             -- 軟刪除時間（未刪除時為 NULL）
    deleted         BOOLEAN                -- 軟刪除標記：false=有效，true=已刪除
);

-- ============================================================================
-- 資料表 9：ip_ban（自動封禁記錄）
-- 用途：記錄防禦引擎自動產生之封禁（含到期時間），供到期自動解封及人工查詢
-- 封禁流程：命中規則 → 寫 DENY 落 domain_ip_rule（IpFilter 立即 403）
--          → 寫本表記錄 → 背景線程掃描 expire_time < now 自動解封並軟刪
-- ============================================================================
CREATE TABLE IF NOT EXISTS ip_ban (
    id                  CHAR(16) PRIMARY KEY,  -- 主鍵：封禁記錄唯一識別碼
    ip_defense_rule_id  CHAR(16),              -- 關聯 ip_defense_rule.id：觸發封禁之規則
    domain              VARCHAR(200),          -- 封禁寫入之網域（全域規則為 "0"）
    port                VARCHAR(50),           -- 封禁寫入之連接埠（全域規則為 "0"）
    ip                  VARCHAR(200),          -- 被封禁之 IP 地址
    ban_time            TIMESTAMP,             -- 封禁開始時間
    expire_time         TIMESTAMP,             -- 封禁到期時間（= ban_time + ban_seconds）
    created_at          TIMESTAMP,             -- 建立時間（與 ban_time 相同）
    updated_at          TIMESTAMP,             -- 最後更新時間
    deleted_at          TIMESTAMP,             -- 軟刪除時間（解封時填入）
    deleted             BOOLEAN                -- 軟刪除標記：false=封禁中，true=已解封
);
