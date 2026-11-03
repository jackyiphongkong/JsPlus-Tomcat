# JsPlus-Tomcat

**輕量級的 Java 原生 Nginx 替代方案。** 以內嵌 **Apache Tomcat 11** 構建的反向代理、網頁伺服器與多網域閘道：各網域的路由、基本認證、IP 允許／拒絕、攻擊防禦與 TLS，全部是內嵌 H2 資料庫中的即時資料列，並可於 GraalVM JS 控制台以單行呼叫管理。毋須 XML 伺服器設定、毋須插件層、毋須任何外部安裝——整套堆疊（Tomcat + H2 + JS 執行環境 + webapps）從單一資料夾、於同一個 JVM 內啟動。

## 主要功能

| 功能 | 說明 | 對應類 |
| --- | --- | --- |
| **反向代理** | 把 `domain:port` 路由到後端目標；剝除逐跳標頭、可設定轉發與回應標頭；支援各網域的 JS `filter()`（改寫回應）與 `preRequest()`（快取／樁式短攔）掛鈎 | `DomainProxy` |
| **各網域基本認證** | 每個 `domain:port` 獨立的認證開關與使用者清單，密碼以 AES 加密、查詢結果快取，連續失敗即鎖定帳號 | `SecurityFilter` |
| **IP 存取控制** | 每列一條 ALLOW／DENY 規則，按 `domain:port` 生效；支援精確 IP、CIDR 與通配符；`0:0` 為全伺服器預設；允許優先於拒絕 | `IpFilter` |
| **IP 攻擊防禦** | 批次寫入請求日誌，並以滑動窗口規則自動封禁超標 IP 至 `domain_ip_rule`，到期自動解封 | `IpAttackDefense` |
| **內嵌 Tomcat** | 以程式方式啟動／停止／重啟 Tomcat 11；多 webapp 部署；連線器與資源調優 | `TomcatServer` |
| **HTTP/2** | TLS 上以 ALPN 提供 `h2`，純文字連線以 HTTP/1.1 Upgrade 或 prior knowledge 提供 `h2c` | `TomcatServer` |
| **熱重載 SSL** | 於運行中的連線器新增或重載主機憑證；資料庫中的主機記錄每次啟動自動套用 | `TomcatServer` |
| **腳本控制台池** | 以固定上限的 GraalVM JS 控制台池供逐請求腳本取用，並提供使用量指標 | `TomcatScriptConsole` |
| **網頁存取日誌** | 異步批次寫入 CSV，支援按大小輪替、歸檔與 URI 允許／忽略規則 | `ServerLog` |
| **資料庫驅動設定** | 認證、映射、SSL、IP 與防禦設定皆為即時資料列；修改即時生效，啟動時自動還原 | H2 `tomcatdb` |

## 運行環境設定

### Java

需要 **GraalVM 25（Java 25）**，放置於 `Java\default`（該目錄不入版本庫，缺失時啟動腳本自動回退系統 `JAVA_HOME`）：

| 目錄 | 作用 |
| --- | --- |
| `Java\default` | 建置與啟動用完整 JDK（含 `jmods`） |

### Ant（可選）

`Ant\default` 隨附 Ant；缺失時回退系統 `ANT_HOME`。建置入口：`ant.bat`（Linux/macOS：`ant.sh`）。僅在重新打包專案 JAR 時需要，運行本身毋須。

### 依賴

H2、Tomcat、GraalVM 與 JsPlus 執行環境皆以普通 JAR 形式隨附於 `LibHome/`。不涉及 Maven／Gradle 依賴解析，亦毋須伺服器或 IDE 插件；升級任何函式庫只需替換 JAR 並重新建置。

## 快速啟動

首次運行只需建庫一次：

| 步驟 | 指令 | 作用 |
| --- | --- | --- |
| 0 | `JsPlus\bin\StartH2Console.bat tomcatdb` | 開啟 `tomcatdb` 的 H2 SQL 控制台，於其中執行 `dbscript\change.sql` |

日常啟動：

| 步驟 | 指令 | 作用 |
| --- | --- | --- |
| 1 | `JsPlus\bin\StopAll.bat` | 停止所有服務 |
| 2 | `JsPlus\bin\BG_StartH2.bat` | 後台啟動 H2，隱藏窗口（TCP `3307`） |
| 3 | `JsPlus\bin\BG_StartTomcat.bat` | 後台啟動內嵌 Tomcat（HTTP `8080`、HTTPS `8443`） |

然後開啟隨附的範例 webapp：

```
http://localhost:8080        HTTP
https://localhost:8443       HTTPS，開發用自簽憑證位於 JsPlus\key\
```

首個參數可指定腳本引擎，例如 `BG_StartTomcat.bat graalvm.js`（預設）。若需保留控制台窗口，改用前景版本 `StartH2` / `StartTomcat`。`StopTomcat` / `StopH2` 停止單一服務，`StopAll` 停止全部。

## 設定

### `JsPlus/config/Tomcat.properties`

| 設定項 | 預設值 | 說明 |
| --- | --- | --- |
| `port` / `sslPort` | `8080` / `8443` | HTTP 與 HTTPS 連線器埠 |
| `Webapp.contextPath[n]` / `Webapp.appBase[n]` | `/` → `webapps/default/` | webapp 部署（可任意多組配對） |
| `maxThreads` / `maxPostSize` / `maxSavePostSize` | `1000` / `200MB` | 工作執行緒數與請求大小上限 |
| `connectionTimeout` / `keepAliveTimeout` | `300000` | 連線器逾時（毫秒） |
| `reloadable` | `true` | 已部署 webapp 的熱重載 |
| `SSL.enable` / `SSL.keyRoot` | `true` / `${JS_PLUS_PATH}/key/` | SSL 開關與金鑰庫根目錄 |
| `SSL.keystoreFile[0]` / `SSL.keystorePass[0]` / `SSL.keyAlias[0]` | `default_keystore.jks` / `changeit` / `client` | 預設憑證 |
| `SSL.truststoreFile` / `SSL.truststorePass` / `SSL.truststoreType` | `default_truststore.jks` / `changeit` / `JKS` | 用戶端認證信任庫 |
| `Http2.enable` / `Http2.maxConcurrentStreams` | `true` / `200` | HTTP/2 開關與每連線並行流上限 |
| `TomcatScriptConsole.poolSize` / `cacheSeconds` / `freedDelayMs` | `100` / `2` / `10` | 控制台池大小、參數快取、歸還延遲 |
| `TomcatScriptConsole.engineName` | `graalvm.js` | JS 引擎（可回退 nashorn） |
| `DomainProxy.enable` | `true` | 於 `/*` 註冊反向代理 servlet |
| `DomainProxy.proxyConnectTimeout` / `proxyRequestTimeout` | `60s` / `30s` | 上游連線與整段請求逾時 |
| `SecurityFilter.cacheSeconds` | `5` | 認證檢查快取時長（秒）；`0` 表示不快取 |
| `IpFilter.enable` / `IpFilter.cacheSeconds` | `true` / `30` | IP 過濾器開關與規則快取時長 |
| `IpAttackDefense.enable` / `logTimer` / `ruleCacheSeconds` | `true` / `1000` / `60` | 防禦開關、批次寫入間隔（毫秒）、規則快取 |
| `IpAttackDefense.logRetention` / `logPhysicalDelete` | `7d` / `30d` | `ip_request_log` 的軟刪除與物理刪除年限（`0` 關閉） |
| `errorRedirectEnable` / `errorRedirectUrl` | `false` | 以 `302` 取代錯誤回應內容 |

`DomainProxy.enable=false`（按鍵缺失時亦為程式碼預設）會跳過代理 servlet：由已部署的 webapp 直接回應請求，伺服器即等同單純的內嵌 Tomcat 網頁伺服器。認證過濾器不論模式與否都會註冊。額外連線器屬性可經 `JsPlus/config/Attributes.properties` 提供，重載時套用至所有連線器。

其餘連線器控制項僅在設定時套用（`-1` 或留空則保留 Tomcat 預設）：`maxConnections`、`asyncTimeout`、`connectionUploadTimeout`、`disableUploadTimeout`、`maxCookieCount`、`maxParameterCount`、`encoding`、`compressionEnable`、`compressableMimeType`、`compressionMinSize`。

### `JsPlus/config/Appenv.properties`

環境值如 `TOMCAT_PORT`、`H2_PORT`（預設 `3307`，可用 `JsPlus_H2_PORT` 覆寫），以及應用程式取用的 `Custom_*` / `Env_*` 鍵。

### `JsPlus/config/DataSource.properties`

`jdbc:h2:tcp://localhost:${H2_PORT}/tomcatdb` 的 H2 連線池，使用者 `sample_user`，密碼由 `dbscript/change.sql` 建立。

### 網頁日誌（`WebLog.*`）

| 設定項 | 預設值 | 說明 |
| --- | --- | --- |
| `WebLog.Enable` | `true` | 總開關 |
| `WebLog.Folder` | `${JS_PLUS_PATH}/logs/domain` | CSV 輸出目錄 |
| `WebLog.MaxFileSize` / `MaxBackupIndex` | `10MB` / `10` | 輪替大小與保留檔案數 |
| `WebLog.ArchiveDays` | `31` | 刪除超過 N 日的日誌 |
| `WebLog.ParamMaxLength` | `20` | 截斷日誌中的參數與標頭 |
| `WebLog.Allowed` / `WebLog.Ignored` | — | 以正則表示式篩選記錄內容 |

## 資料庫（H2）

`dbscript/change.sql` 建立執行期資料表；`dbscript/fallback.sql` 將其全部刪除。

| 資料表 | 用途 |
| --- | --- |
| `domain_auth` | 認證開關、各網域 AES `encrypt_key`，以及鎖定策略 |
| `domain_auth_user` | 使用者名稱、AES 加密密碼與各帳號鎖定狀態 |
| `domain_mapping_param` | 網域至代理目標的參數列 |
| `domain_ssl_host` | 額外 SSL 主機憑證（`keystore_pass` 逐列加密） |
| `domain_ip_rule` | IP 規則，每列一條 ALLOW／DENY 加一個樣式（含手動與自動封禁列） |
| `ip_request_log` | 逐請求記錄，批次寫入 |
| `ip_defense_rule` | 攻擊防禦閾值，可選擇以 URI 正則收窄 |
| `ip_ban` | 自動封禁記錄，含 `expire_time` |
| `ghost_remote_command` | Ghost 控制台使用的遠端指令佇列 |

## 網域代理與安全

### 反向代理（`DomainProxy`）

每個請求按 `domain:port` 解析，先查 `domain_mapping_param`，再查各網域腳本 `JsPlus/domain/<domain>_<port>.js`：

```js
DomainMapping.getParams = function () {
    return {
        target: 'https://killcoding.com',
        connectTimeout: '60s',
        forwardedHeaders: { header_A: 'value_1' },
        responseHeaders:  { header_1: 'value_1' },
        forwardedHeader: true,
        filterEnable: true
    };
};
```

兩個可選掛鈎延伸此流程。`DomainMapping.filter()` 於回應返回前改寫上游內容（兼容 gzip）。`DomainMapping.preRequest()` 於**上游呼叫之前**執行，作為快取／樁式短攔：回傳 `null` 表示請求照常轉發，回傳物件則直接回應、完全不觸及後端：

```js
DomainMapping.preRequest = function () {
    if (request.getRequestURI() === '/health') {
        return { status: 200, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: 'ok' };
    }
    return null;   // 繼續代理
};
```

回傳的 Map 可含 `status`（數字，預設 `200`）、`headers`（名稱→值）與 `body`（`string` 或 `byte[]`）。被短攔的請求不會到達上游。可於控制台以 `tomcat.setDomainMapping(domain, port, params)`、`removeDomainMapping`、`queryDomainMapping(s)` 管理映射，修改即時進入代理快取。無映射的 `domain:port` 回應 `502 Domain config not found`。

### 基本認證（`SecurityFilter`）

```js
tomcat.createDomainAuth('127.0.0.1', '8443', true);
tomcat.createDomainAuthUser('127.0.0.1', '8443', 'Sample', 'password');
```

密碼以各網域的 `encrypt_key` 作 AES 加密儲存。**帳號鎖定**：密碼錯誤累加 `failed_attempts`，達到 `max_attempts` 即鎖定至 `locked_until`，到期後下一次嘗試自動解鎖——毋須計時任務。鎖定期間即使密碼正確亦以 `401` 拒絕。登入成功重設計數，修改密碼亦會清除鎖定。策略預設為 `max_attempts=5`、`lock_minutes=30`，可按網域以 `setAuthLockPolicy` 調整。

### IP 存取控制（`IpFilter`）

每列一條規則（`ruleType` 加一個 `ipPattern`），按以下次序判定：

1. **允許優先** —— 命中的 ALLOW 規則放行，即使同時命中 DENY。
2. **拒絕** —— 命中 DENY 規則以 `403` 拒絕。
3. **白名單模式** —— 設有允許規則但全部不命中的網域，拒絕該 IP。
4. **無規則** —— 請求繼續交由 `SecurityFilter` 與 webapp 處理。

`domain="0"`、`port="0"` 的資料列為全伺服器預設，套用至所有本身無規則的 `domain:port`；精確規則永遠優先於 `0:0`。

```js
tomcat.createDomainIpRule("0", "0", "ALLOW", "192.168.1.0/24");
tomcat.createDomainIpRule("sample.com", "8080", "DENY", "203.0.113.7");
```

### IP 攻擊防禦（`IpAttackDefense`）

過濾器在過濾鏈中先於 `IpFilter` 執行。每個請求推入 `CacheArray` 佇列，由背景執行緒批次寫入 `ip_request_log`，熱路徑不會阻塞於 JDBC。每條已啟用規則為每個 IP 維護滑動窗口計數（鍵為規則加 IP）。達到閾值時，引擎寫入 DENY 列至 `domain_ip_rule`（`IpFilter` 即時封鎖）、於 `ip_ban` 記錄封禁與 `expire_time`，到期後兩者一併解除。防禦或資料庫故障絕不阻塞請求處理。

```js
tomcat.createDefenseRule("burst-limit", "0", "0", "", 60, 120, 600, true);
tomcat.createDefenseRule("api-login-burst", "sample.com", "8080", "^/api/login", 60, 10, 3600, true);
tomcat.queryIpBans();
tomcat.removeIpBan("sample.com", "8080", "203.0.113.7");
```

### SSL／TLS

開發用憑證隨附於 `JsPlus/key/`。額外主機可透過 `addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` 或 `loadDomainSslHosts()` 套用至運行中的連線器，毋須重啟；`createDomainSslHost` / `updateDomainSslHost` / `removeDomainSslHost` 僅修改資料庫記錄，於下次啟動生效。可選的雙向 TLS 使用 `clientAuth` 與上述信任庫設定。

## 管理 API

所有方法皆位於 `JsPlus/script/TomcatEmbed.js` 曝露的 `tomcat` 物件上，執行後把 `resMap.msg` 印至控制台並以字串回傳。

### 生命週期

| 方法 | 說明 |
| --- | --- |
| `tomcat.start()` / `stop()` / `restart()` | 載入 `Tomcat.properties` 並啟動、停止或重啟內嵌 Tomcat |

### 基本認證

| 方法 | 說明 |
| --- | --- |
| `createDomainAuth(domain, port, authEnable)` / `updateDomainAuth` / `removeDomainAuth` | 管理 `domain:port` 的認證開關 |
| `queryDomainAuth(domain, port)` / `queryDomainAuths()` | 讀取單一或全部認證設定 |
| `createDomainAuthUser(domain, port, username, plaintextPass)` / `updateDomainAuthUser` / `removeDomainAuthUser` | 管理使用者（密碼以 AES 加密） |
| `setAuthLockPolicy(domain, port, maxAttempts, lockMinutes)` / `unlockAuthUser(domain, port, username)` | 鎖定策略與手動解鎖 |

### 網域映射

| 方法 | 說明 |
| --- | --- |
| `setDomainMapping(domain, port, domainParams)` | 建立或更新映射，即時生效 |
| `removeDomainMapping(domain, port)` | 移除映射 |
| `queryDomainMapping(domain, port)` / `queryDomainMappings()` | 讀取單一或全部映射 |

### SSL 主機

| 方法 | 說明 |
| --- | --- |
| `createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` / `updateDomainSslHost` / `removeDomainSslHost` | 管理資料庫記錄（重啟後生效） |
| `queryDomainSslHost(hostname)` / `queryDomainSslHosts()` | 讀取單一或全部 SSL 主機 |
| `addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 套用至運行中的伺服器，新記錄同時持久化 |
| `loadDomainSslHosts()` | 從資料庫重載全部主機並套用至運行中的伺服器 |

### IP 存取規則

| 方法 | 說明 |
| --- | --- |
| `createDomainIpRule(domain, port, ruleType, ipPattern)` | 新增一條手動規則（`ALLOW` / `DENY`） |
| `updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)` | 修改一條手動規則的樣式；自動封禁列不受影響 |
| `removeDomainIpRule(domain, port, ruleType, ipPattern)` | 軟刪除一條手動規則；解除自動封禁請用 `removeIpBan` |
| `queryDomainIpRule(domain, port, ruleType, ipPattern)` / `queryDomainIpRules()` | 讀取單一或全部規則，每列標註 `manual` 或 `ip_ban` |
| `loadDomainIpRules()` | 清空規則快取，令下一請求重新讀取資料庫 |

### IP 攻擊防禦

| 方法 | 說明 |
| --- | --- |
| `createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | 新增規則（`"0","0"` 為全伺服器；`uriPattern` 可選） |
| `updateDefenseRule(ruleId, ...)` / `removeDefenseRule(ruleId)` | 更新或軟刪除規則 |
| `queryDefenseRules()` / `queryIpBans()` | 列出規則或現行封禁 |
| `removeIpBan(domain, port, ip)` | 即時解除封禁，並刪除對應 DENY 規則 |
| `queryIpRequestLogs()` / `clearIpRequestLogs()` | 檢視最新記錄或清空日誌 |

## 快速測試

| 步驟 | 指令 | 作用 |
| --- | --- | --- |
| 1 | `test\TestAll.bat`（Linux/macOS：`test\TestAll.sh`） | 全量測試：`StopAll` → 清理 `.tmp` 與 `logs` → 初始化資料庫 → 啟動 H2 → 啟動內嵌 Tomcat → **139 項檢查** → 對獨立 Tomcat 執行壓力測試 → 產出報告並歸檔 |

單一套件可執行對應的 `test\Test<Suite>.bat` / `.sh`；每個腳本自行停止服務、準備 H2 並寫出專屬報告，因此毋須全量運行亦可驗證單一模組。

| 套件 | 覆蓋範圍 |
| --- | --- |
| `TestDomainProxy.js` | 26 個案例——映射 CRUD，以及實際反向代理請求、分派與參數校驗 |
| `TestSecurityFilter.js` | 16 個案例——認證與使用者 CRUD，以及實際 `401` / `200` 檢查 |
| `TestIpFilter.js` | 20 個案例——規則 CRUD，以及實際 `403` / `200` 檢查與允許優先於拒絕 |
| `TestIpAttackDefense.js` | 16 個案例——防禦規則 CRUD 與實際自動封禁流程 |
| `TestTomcatServer.js` | 36 個案例——連線器契約、webapp 設定、金鑰庫就緒、SSL 主機 CRUD、HTTP/2 握手 |
| `TestAuthLockout.js` | 7 個案例——鎖定閾值、鎖定期間拒絕、解鎖與計數重設 |
| `TestFreed.js` | 6 個案例——共用基礎設施的煙霧檢查 |
| `TestServerLog.js` | 11 個案例——異步日誌流程、CSV 格式、批次寫入與各網域隔離 |

每個案例印出 `[PASS]` / `[FAIL]`，並於 `docs/latest/report/` 寫出色標 HTML 與 Markdown 報告——每套件一對，另加 `TestAll-Report` 與 `TestAll-CI-Summary.md`。非查詢類案例附 `dbVerify` 區塊，列出實際執行的 `SELECT` 與結果列；每個套件以清理案例作結，確保重複運行互不污染。

套件亦可於運行中的控制台驅動：

```js
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestAll.js');
/
```

壓力測試（`test/LoadTest.js`）為純 JS 腳本，經控制台執行。預設為 `https://localhost:8443/default/`、10 並行 × 1000 請求、30 秒逾時——`TestAll` 把總數提高至 10000；可用 `LOADTEST_URL`、`LOADTEST_CONCURRENCY`、`LOADTEST_TOTAL`、`LOADTEST_TIMEOUT` 覆寫。輸出 `docs/latest/report/loadtest-result.json`（QPS、平均值、p50/p90/p95/p99、最大值、狀態分佈、傳輸錯誤）與離線儀表板 `loadtest-dashboard.html`。兩者皆記錄該次運行的執行環境——作業系統、CPU 核心、記憶體、JVM 堆、JDK 與 JVM 參數——因此 QPS 只宜與同一機器上取得的運行比較。

## 建置

```sh
# Linux / macOS
ant

# Windows
ant.bat
```

編譯 `src/` 並把 `com/**` 與 `org/**` 打包為 `LibHome/jsplus/default/jsplus-tomcat-<version>.jar`。每次成功建置會遞增 `version.properties` 的 patch、於 `changes.log` 與 `changes_zh-HK.log` 各追加一行帶日期的記錄，並重新產生 API 站至 `docs/latest/api/`，另存檔於 `docs/apis/<version>.jar`（以 `-Djavadoc.skip=true` 略過）。以 `-Dchange.note="..."` 可於記錄行附上說明。

`ant.sh` / `ant.bat` 先從隨附的 `Java/default/` 與 `Ant/default/` 解析 `JAVA_HOME` 與 `ANT_HOME`，缺失時回退系統值或 `PATH`。

## AI-Agent 協作開發

JsPlus-Tomcat 從設計上即支援 AI Agent 參與開發——環境自動化、AI 生成代碼與測試、全自動驗證、人為把關報告，形成完整閉環。

| 環節 | 說明 |
| --- | --- |
| **初始環境自動化** | `ant.bat` 一條命令完成建置（版本管理 → 打包 → javadoc）；只需 GraalVM 25（Ant 可選）；Windows 與 Linux 共用同一套 `.bat` / `.sh` 腳本，環境可重現 |
| **AI 幫手寫 Code** | 內嵌 GraalVM JS 控制台可即時執行新邏輯，毋須重啟、毋須重編譯；整個伺服器以 `tomcat` 物件暴露 |
| **AI 寫測試案例** | 統一 JS 套件範式（`Case Cxx` 命名 + `TestBase` 測試框架）可直接納入 `TestAll`；每個模組另有專屬啟動腳本，支援快速單套件循環 |
| **全自動驗證** | 本地 `TestAll.bat` / `TestAll.sh` 一鍵全量；CI（GitHub Actions）自動建置、執行套件並歸檔報告 |
| **人為檢測測試報告** | `TestAll-Report` 與 `TestAll-CI-Summary` 詳列每案例結果、`dbVerify` 輸出與失敗細節；測試判斷交給機器，質量把關交給人 |

## 授權

以 **MIT License** 發佈，全文見 [LICENSE](LICENSE)。
