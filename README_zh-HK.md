# JsPlus-Tomcat

> **一款輕量級的 Java 原生 Nginx 替代方案。** 一個 JVM、一個目錄：以內嵌 **Apache Tomcat 11** 構建的
> 反向代理、Web 伺服器與按網域閘道——可腳本化、零外掛程式。
> **下載解壓後立即可用**——無需安裝程式、無需系統級別的設定，解壓即可開始服務。

專為 JsPlus 執行環境（`com.killcoding.jsplus.tomcat`）設計的內嵌式 **Apache Tomcat 11** 伺服器。
與 Nginx 相似，它從單一程序同時服務多個網域，但以純 Java 實現：按網域的反向代理、按網域的
Basic Auth、可熱載入的 SSL 憑證、請求資源分配與 GraalVM JS 請求主控台——無需 XML 伺服器設定、
無需外部安裝、無需管理外掛程式。它以 Java 程式碼啟動整套 Tomcat，並在其上加入可腳本化的
擴充層，因此整個伺服器（啟動、停止、路由、驗證、SSL）皆可於 JS 主控台中以一行指令管理。

> 版本：`7.0.0 (2026.10.02)` · 基礎來源：`apache-tomcat-11.0.1-embed.zip`

---

## 特色亮點（與傳統 Tomcat 的差異）

大多數嵌入式 Tomcat 專案僅止於嵌入引擎。JsPlus-Tomcat 將整個伺服器轉化為
**可腳本化、由資料庫驅動的執行環境**：

- **Java 原生的 Nginx 替代方案** — Nginx 風格的反向代理、按網域虛擬主機、SSL 終止、IP 攻擊防禦
  與連線/資源控制，全部於單一 JVM 內運行、零外掛程式；無需額外部署、設定或維護一層反向代理。
- **整個伺服器即為一個 JS 物件** — 啟動、停止、重啟，以及所有管理操作
  （`createDomainAuth`、`setDomainMapping`、`createDomainSslHost`、`addSslHost`、
  `loadDomainSslHosts`、`createDomainIpRule`、`createDefenseRule` 等）皆可於 GraalVM JS
  主控台中一行指令完成。無需修改 XML、無需重啟。
- **執行期設定存放於 H2，而非設定檔** — `domain_auth`、`domain_mapping_param`、
  `domain_ssl_host`、`domain_ip_rule` 與 `ip_defense_rule` 即為執行期的真正設定來源：auth、
  IP 存取、網域對應與攻擊防禦的變更即時生效（代理快取／驗證檢查），SSL 主機變更遵循下方
  熱載入規則，並於啟動時自動重新套用。
- **單一 Tomcat 內的按網域多租戶架構** — 每個 `domain:port` 擁有各自的反向代理目標、
  各自的 Basic Auth、各自的 IP 允許／拒絕規則、各自的攻擊防禦規則與各自的 SSL 憑證，
  全部獨立管理。
- **SSL 可熱載入** — 新憑證可透過 `addSslHost(...)` 熱載入至執行中的伺服器；`updateDomainSslHost()`
  與 `removeDomainSslHost()` 僅變更資料庫記錄，需要重啟 Tomcat 才能生效。
- **單一 JVM 的單機部署** — 一個目錄、一個 JVM：Tomcat + H2 + GraalVM JS 執行環境 +
  webapps，無需安裝外部伺服器或 DBMS。
- **精細的請求資源分配** — connector 調校（`maxThreads`、`maxConnections`、逾時、大小限制、
  壓縮）搭配 JS 主控台資源池，限制可腳本化處理的請求量。
- **代碼簡單、維護輕鬆** — 伺服器僅為單一套件內數個純 Java 類別；所有依賴皆為獨立 JAR，
  集中存放於 `LibHome/`，容易查找與隨附——無需 Maven/Gradle 依賴解析、無需安裝 IDE 或
  伺服器外掛程式。升級內嵌 Tomcat（或任何函式庫）只需替換對應 JAR 並以 `ant` 重新建置：
  無需遷移設定、無需管理外掛程式。

| 傳統 Tomcat | JsPlus-Tomcat |
|---|---|
| `server.xml` + 修改後需重啟 | JS console API，執行時立即生效 |
| SSL 變更需重啟 | `addSslHost()` 熱載入（新主機）；update/remove 需重啟 |
| 靜態 webapp 部署 | 以程式部署 webapp，並提供按網域的代理、驗證與 SSL |
| 外部 DB / 額外安裝 | 單一 JVM 內嵌 H2 + GraalVM JS |

與 **Nginx** 相比：

| Nginx | JsPlus-Tomcat |
|---|---|
| 以 C 語言撰寫；需原生安裝 + 編輯 `nginx.conf` + `nginx -s reload` | 純 Java 內嵌 Tomcat；下載解壓即用——無需安裝 |
| 靜態設定檔；變更需修改檔案並重新載入 | 可從 JS 主控台腳本化、以資料庫驅動設定，執行時即時生效 |
| 以編譯的 C 模組擴充功能 | 以 JS 腳本（GraalVM）及純 JAR 依賴擴充 |
| SSL 憑證需按 server block 手動管理 | SSL 可熱載入、DB-backed 主機、按網域憑證 |

---

## 功能特色

- **內嵌式 Tomcat 11** — 透過 `TomcatServer` 以程式化方式啟動，不需要 XML 伺服器設定檔。
- **多 Webapp 部署** — 可從 `tomcat.properties` 設定任意數量的 `Webapp.contextPath[n]` / `Webapp.appBase[n]` 組合。
- **HTTP + HTTPS 雙 Connector** — 預設 `8080`（HTTP）與 `8443`（HTTPS）；支援依主機（host）設定金鑰庫，並可在
  **不重啟伺服器** 的情況下 **熱載入 SSL 憑證**。
- **Web 伺服器與請求資源分配** — 提供 HTTP/HTTPS 服務，並對網絡請求如何消耗資源提供精細控制：
  工作者執行緒（`maxThreads`）、連線上限（`maxConnections`）、逾時、請求大小限制、GZIP 壓縮，
  以及可供每個請求以腳本處理的 JS 主控台資源池。
- **Connector 調校** — `maxThreads`、`maxPostSize`、`maxSavePostSize`、`maxCookieCount`、
  `maxParameterCount`、`asyncTimeout`、`connectionTimeout`、`keepAliveTimeout`、`maxConnections`、
  上傳逾時與 GZIP 壓縮，全部皆可設定。
- **HTTP/2** — SSL Connector 上透過 ALPN 協商 `h2`，明文 Connector 上以
  HTTP/1.1 Upgrade／prior knowledge 支援 `h2c`（`Http2.enable`、
  `Http2.maxConcurrentStreams`）。
- **外部 Web 資源** — 可將額外 JAR（`jarResourcePath`）、外部 classes 目錄（`classesPath`）
  與外部靜態目錄（`webResourcePath` + `webResourceMount`）掛載進 webapp。
- **DomainProxy（反向代理）** — 將 `domain:port` 對應到後端目標，並可按網域以 JS 腳本處理
  （見 `JsPlus/domain/*.js`），支援逐跳（hop-by-hop）標頭剝離、轉發/回應標頭，
  以及 JS `filter()` 掛鉤（具 gzip 感知的 body 改寫）。
- **SecurityFilter（Basic Auth）** — 以 H2 為後端、依 `domain:port` 的驗證，密碼以 AES 加密、
  快取查詢，並提供完整的 JS 主控台管理 API。
- **IpFilter（IP 允許／拒絕）** — 以 H2 為後端、依 `domain:port` 的存取控制：每行一條規則
  （`ALLOW`／`DENY` + 單一 IP 規則），支援精確 IP、CIDR 網段與萬用字元，並提供 `0:0`
  全伺服器後備規則；被拒絕的請求回應 `403`，變更即時生效。
- **IpAttackDefense（IP 攻擊防禦）** — 每個請求皆以非同步批次方式記錄至 `ip_request_log`
  （CacheArray + CacheDriverExecutor）；可設定的規則按 `domain:port`（可另加 URI 正則）
  於滑動時間窗內按 IP 計數，超出門檻的 IP 會自動寫入 `domain_ip_rule`（DENY）封禁，
  直至封禁期滿自動解除。
- **TomcatScriptConsole** — 一組 GraalVM JS 腳本主控台的資源池，可用腳本擴充請求處理；
  資源池大小可動態調整，並有使用量指標。
- **ServerLog** — 非同步 CSV Web 存取日誌，支援批次寫入、依大小輪替、
  封存與 URI 允許/忽略規則。
- **優雅關閉** — `stop()` 附 30 秒強制關閉的後備機制。
- **單機部署（Standalone deployment）** — Web 伺服器、H2 資料庫、JS 執行環境與 webapps
  全部從單一目錄（`JsPlus/`）於單一 JVM 程序中啟動；無需安裝外部 Tomcat 或資料庫伺服器。

---

## 架構

```
JsPlus runtime (JS layer)                    Java layer (this project)
┌──────────────────────────────┐   ┌──────────────────────────────────────┐
│ script/StartTomcat.js        │   │ com.killcoding.jsplus.tomcat          │
│   └─ TomcatEmbed.js          │──▶│  TomcatServer      (embedded Tomcat)  │
│      start()/stop()/restart()│   │  ├─ IpFilter       (IP allow/deny)    │
│      domain-auth APIs        │   │  ├─ SecurityFilter (per-domain auth)  │
│      domain-auth APIs        │   │  ├─ DomainProxy    (reverse proxy)    │
│   └─ domain/*.js mapping     │   │  ├─ ServerLog      (CSV access logs)  │
│   └─ ghost scripts (H2/Tomcat)│  │  └─ TomcatScriptConsole (GraalVM pool)│
└──────────────────────────────┘   └──────────────────────────────────────┘
                                              │
                          ┌───────────────────┼────────────────────┐
                          ▼                   ▼                    ▼
                    H2 database        webapps (app/trade)    SSL keys (key/)
                    tomcatdb
```

**執行流程**

```
JsPlus/bin/StartH2.sh（或 BG_StartH2.sh，背景執行）
        │
        ▼
JsPlus/bin/StartTomcat.sh（或 BG_StartTomcat.sh）
        │
        ▼
com.killcoding.jsplus.Main（java -cp "<LibHome>/*" -ScriptEngine graalvm.js -Env script -WriteHistory false -GhostName H2Ghost -Task script/StartTomcat.js）
        │
        ▼
TomcatEmbed.js ──► 讀取 config/tomcat.properties，建立參數對應表
        │
        ▼
TomcatServer（背景執行緒）
        ├──► 建立 HTTP/HTTPS connectors
        ├──► 部署所有已設定的 webapp
        ├──► 註冊 IpFilter（按網域 IP 允許／拒絕）
        ├──► 註冊 SecurityFilter（按網域 Basic Auth）
        └──► 啟用代理時註冊 DomainProxy servlet
        │
        ▼
載入網域對應（domain mappings）
        ├──► H2：domain_mapping_param
        └──► JsPlus/domain/ 的按網域 JS 腳本
```

---

## 存放庫結構

```
JsPlus-Tomcat/
├── src/                        # 本專案的 Java 原始碼
│   └── com/killcoding/jsplus/tomcat/
│       ├── TomcatServer.java        # 內嵌 Tomcat 啟動與生命週期
│       ├── SecurityFilter.java      # 按網域 Basic Auth 過濾器
│       ├── IpFilter.java            # 按網域 IP 允許／拒絕過濾器
│       ├── DomainProxy.java         # 可腳本化的反向代理 servlet
│       ├── ServerLog.java           # 非同步 CSV Web 日誌
│       └── TomcatScriptConsole.java # GraalVM JS 主控台資源池
├── JsPlus/                     # JsPlus 執行環境主目錄 (JS_PLUS_PATH)
│   ├── bin/                    # 啟動/停止腳本 (.sh / .bat)
│   ├── config/                 # tomcat.properties、DataSource、Logger、env …
│   ├── script/                 # JS 啟動腳本：StartTomcat.js、TomcatEmbed.js …
│   ├── domain/                 # 按網域對應腳本 (domain:port.js)
│   ├── webapps/                # 內附的範例 webapp (app、trade)
│   ├── key/                    # 開發用 keystore.jks / truststore.jks
│   ├── ghost/                  # ghost 輔助腳本 (H2Ghost、TomcatGhost)
├── LibHome/                    # 依賴 JAR (tomcat、h2、graalvm、nashorn、jsplus …)
├── dbscript/                   # change.sql / fallback.sql (H2 schema)
├── build.xml                   # Ant 建置 → LibHome/jsplus/default/jsplus-tomcat.jar
├── ant.bat                     # Windows Ant 包裝腳本
└── ant.sh                      # Linux/macOS Ant 包裝腳本
```

---

## 環境需求

- **Java** — 位於 `../Java/default/` 的 JDK（例如 GraalVM JDK 25），或可透過
  `JAVA_HOME`／PATH 取得的任何 JDK（啟動腳本會自動回退）。
- **Apache Ant** — 僅在需要重新建置 `jsplus-tomcat.jar` 時使用（執行時不需要）；內附於
  `ant/default/`，缺失時回退至系統 `ANT_HOME`／PATH。
- **H2** — 內附於 `LibHome/h2/default/`。

---

## 建置

編譯 `src/` 並將 `com/**` 與 `org/**` 打包成：

```
LibHome/jsplus/default/jsplus-tomcat.jar
```

```sh
# Linux / macOS
ant

# Windows
ant.bat
```

`ant.sh`（Linux/macOS）／`ant.bat`（Windows）會從腳本旁的 `Java/default/` 與 `ant/default/`
解析 `JAVA_HOME` 與 `ANT_HOME`；若捆綁副本缺失，會回退至系統 `JAVA_HOME`／`ANT_HOME`（或 PATH）。

---

## 快速開始

> 腳本在捆綁 JDK（`JsPlus/../Java/default/bin/java`）存在時優先使用；否則自動回退至
> `JAVA_HOME` 或 PATH 上的 `java`。

> **首次使用——建立 Tomcat 資料庫：** 首次執行前，需先將 `dbscript/change.sql` 套用至主資料庫
> （`tomcatdb`）一次，以建立伺服器所需的使用者與資料表：
> 1. `JsPlus/bin/StartH2Console.sh tomcatdb`（Windows 為 `.bat`）——開啟連接到 `tomcatdb` 的 H2 SQL 主控台。
> 2. 複製 `dbscript/change.sql` 的內容，貼入主控台執行（或輸入 `RUNSCRIPT FROM '<change.sql 完整路徑>';`）。

```sh
# 1. 啟動 H2 資料庫伺服器（預設 TCP 埠 3306，見 Appenv.properties）
JsPlus/bin/StartH2.sh          # Windows 請用 StartH2.bat

# 2. 啟動內嵌 Tomcat（預設 HTTP 8080 / HTTPS 8443）
JsPlus/bin/StartTomcat.sh      # Windows 請用 StartTomcat.bat

# 3. 開啟內附的範例 webapp
#    http://localhost:8080        (HTTP)
#    https://localhost:8443       (HTTPS，開發用自簽憑證位於 key/)
```

注意事項：

- 可傳入腳本引擎參數，例如 `StartTomcat.sh graalvm.js`（預設）。
- 以 `JsPlus/bin/StopAll.sh`（Windows 為 `.bat`）停止全部服務；如需單獨停止 Tomcat 或 H2，
  可用 `StopTomcat`／`StopH2`（無需參數）。
- 如需背景執行（分離運行、不依附主控台視窗），請改用 `JsPlus/bin/BG_StartH2`／
  `BG_StartTomcat`（`.sh`／`.bat`），而非前景執行的 `Start*` 腳本。
- 伺服器亦會啟動 **Ghost** 主控台（`H2Ghost`），用於遠端指令資料表。

---

## 單機部署（Standalone deployment）

本系統設計為一個 **獨立、自足的單元**——將資料夾複製到目標機器、執行兩個腳本，
整套服務（Web 伺服器 + 資料庫 + 腳本執行環境 + webapps）即可啟動。
**下載解壓後立即可用**——發行版即為一個普通資料夾；解壓至任意位置、指向 JDK，
即可立即投入使用。
所有元件皆位於單一目錄並在單一 JVM 程序中執行，因此單一節點即可服務整個系統。

- **單一執行環境主目錄** — `JsPlus/`（`JS_PLUS_PATH`）包含一切：`config/`、`webapps/`、`key/`、
  `script/` 與 `ghost/`；所有依賴 JAR 都收錄在 `LibHome/` 下。
- **內附資料庫** — H2 隨附於 `LibHome/h2/default/`；`bin/StartH2` 啟動本機 H2 TCP
  伺服器，資料檔存放於 `Database/`（已 gitignore）。
- **單一 JVM** — `bin/StartTomcat` 啟動單一 Java 程序（`com.killcoding.jsplus.Main`），
  同時啟動內嵌 Tomcat、H2 資料來源與 GraalVM 腳本主控台。
- **無需外部安裝** — 不需要獨立 Tomcat，也不需要獨立的 DBMS；唯一的外部元件是
  位於 `../Java/` 的 JDK。
- **Windows 與 Linux 體驗一致** — 每個腳本都有 `.bat` 與 `.sh` 雙版本
  （`StartH2`、`StartTomcat`、`StopH2`、`StopTomcat`、`StopAll`、ghost 輔助腳本）。

典型部署步驟：

1. 將 `JsPlus/` + `LibHome/`（以及放入 `Java/default/` 的 JDK）複製到目標機器。
2. 執行 `JsPlus/bin/StartH2` — 啟動 H2 資料庫伺服器。
3. 執行 `JsPlus/bin/StartTomcat` — 於 `8080` / `8443` 啟動內嵌 Tomcat。
4. 以 `JsPlus/bin/StopAll` 停止全部服務。

---

## 網絡請求轉發(Web Server/Proxy)

`JsPlus/config/tomcat.properties` 中的一個開關決定伺服器的運行模式：

| `DomainProxy.enable` | 模式 | 行為 |
|---|---|---|
| `false`（代碼預設，鍵值缺失時） | **單機模式（Standalone mode）** | 不註冊代理 servlet；伺服器直接服務部署於 `JsPlus/webapps/` 下的 webapps（`8080`／`8443`）——即為服務單一網站的普通內嵌 Tomcat Web 伺服器。 |
| `true`（由隨附的 `tomcat.properties` 設定） | **Web Server(Proxy) 模式** | 將 `DomainProxy` servlet 註冊至 `/*`；啟動時從 H2 載入各 `domain:port` 的對應，並將每個請求路由至其後端目標，同時套用按網域 Basic Auth 與可熱載入的 SSL 憑證。適用於以單一 JVM 同時服務多個網域／後端應用程式，如同 Nginx。 |

底層行為：

- `DomainProxy.enable=false`（鍵值缺失時代碼的預設值亦為 `false`）：跳過 `addServlet`，不進行代理路由——
  請求直接由已部署的 webapp 自行提供服務。驗證過濾器仍然註冊，按網域 Basic Auth 繼續生效。
- `DomainProxy.enable=true`：將 `DomainProxy` servlet 對應至 `/*`，啟動時以 `loadDomainMapping()`
  將 `domain_mapping_param` 的全部記錄重放至代理快取；其後以 `tomcat.setDomainMapping(...)`
  建立的對應亦會即時生效。若某 `domain:port` 沒有對應，請求會回應 `502 Domain config not found`。

切換模式只需編輯 `tomcat.properties` 並重啟——下一次啟動即生效。

---

## 設定

### `JsPlus/config/tomcat.properties`

| 金鑰 | 隨附值 | 說明 |
|---|---|---|
| `port` | `${TOMCAT_PORT}` (8080) | HTTP connector 埠 |
| `sslPort` | `8443` | HTTPS connector 埠 |
| `Webapp.contextPath[n]` / `Webapp.appBase[n]` | `/` → `webapps/trade/` | webapp 部署設定 |
| `maxThreads` / `maxPostSize` / `maxSavePostSize` | `1000` / `200MB` | connector 限制 |
| `connectionTimeout` / `keepAliveTimeout` | `300000` | connector 逾時（毫秒） |
| `reloadable` | `true` | 已部署 webapp 的熱載入 |
| `SSL.enable` / `SSL.keyRoot` | `true` / `key/` | SSL 開關與金鑰庫根目錄 |
| `SSL.keystoreFile[0]` / `SSL.keystorePass[0]` / `SSL.keyAlias[0]` | `keystore.jks` / `tomcat` / `tomcat` | 預設憑證 |
| `SSL.truststoreFile` / `SSL.truststorePass` / `SSL.truststoreType` | `truststore.jks` / `tomcat` / `JKS` | 用戶端驗證 truststore |
| `Http2.enable` | `true` | HTTP/2 開關——SSL Connector 經 ALPN 協商 `h2`，明文 Connector 支援 `h2c` upgrade |
| `Http2.maxConcurrentStreams` | `200` | 每條連線的最大並行 HTTP/2 stream 數 |
| `TomcatScriptConsole.poolSize` / `cacheSeconds` / `freedDelayMs` | `100` / `2` / `10` | 腳本主控台資源池調校 |
| `TomcatScriptConsole.engineName` | `graalvm.js` | JS 引擎（可退回 nashorn） |
| `DomainProxy.enable` | `true` | 啟用反向代理 servlet |
| `DomainProxy.proxyConnectTimeout` | `60s` | 上游連線逾時 |
| `DomainProxy.proxyRequestTimeout` | `30s` | 整個請求時限（連線＋回應標頭）；可按網域以 `requestTimeout` 覆寫 |
| `SecurityFilter.cacheSeconds` | `5` | 驗證查詢的快取時間（秒） |
| `IpFilter.enable` | `true` | IP 允許／拒絕過濾器的總開關 |
| `IpFilter.cacheSeconds` | `30` | 按網域 IP 規則的快取時間（秒） |
| `IpAttackDefense.enable` | `true` | IP 請求記錄與攻擊防禦的總開關 |
| `IpAttackDefense.logTimer` | `1000` | `ip_request_log` 批次寫入間隔（毫秒） |
| `IpAttackDefense.ruleCacheSeconds` | `60` | 防禦規則的快取時間（秒） |
| `IpAttackDefense.logRetention` | `7d` | 軟刪除早於此時間的 `ip_request_log` 記錄（0 = 停用；`ms\|s\|m\|h\|d\|w`） |
| `IpAttackDefense.logPhysicalDelete` | `30d` | 物理刪除已軟刪除超過此時間的記錄（0 = 停用） |
| `errorRedirectEnable` / `errorRedirectUrl` | `false` | 改送 302 取代錯誤內文 |
| `WebLog.*` | — | 見下方 *Web 日誌* |

亦可透過 `JsPlus/config/attributes.properties` 提供額外的 connector 屬性
（可熱載入，套用於所有 connectors）。

### 請求資源分配（connector 調校）

除上表參數外，`TomcatServer` 還提供以下網絡/資源控制項。
每一項僅在 `tomcat.properties` 中設定時才會套用；留空或設為 `-1` 表示沿用 Tomcat 預設值：

| 金鑰 | 隨附值 | 控制的項目 |
|---|---|---|
| `maxConnections` | — | connector 接受的最大同時連線數 |
| `asyncTimeout` | — | 非同步請求的逾時（毫秒） |
| `connectionUploadTimeout` | — | 上傳逾時；上傳不受 `connectionTimeout` 限制 |
| `disableUploadTimeout` | `true` | 上傳期間停用連線逾時 |
| `maxCookieCount` | — | 每個請求的最大 cookie 數 |
| `maxParameterCount` | — | 最大請求參數數（表單/查詢字串） |
| `encoding` | `UTF-8` | URI body 編碼 |
| `compressionEnable` | `false` | 啟用 GZIP 回應壓縮 |
| `compressableMimeType` | — | 可壓縮的 MIME 類型 |
| `compressionMinSize` | `1024` | 觸發壓縮的最小回應大小（位元組） |

針對可腳本化的請求處理，**GraalVM JS 主控台資源池** 本身即是一個資源分配的調節鈕：
`TomcatScriptConsole.poolSize` 限制同時可透過腳本處理的請求數，`cacheSeconds` 控制主控台/網域
參數的快取時間，`freedDelayMs` 設定已釋放主控台返回資源池所需的時間。
當資源池使用率超過 80% 時會記錄一則警告。

### `JsPlus/config/Appenv.properties`

可覆寫的環境變數值，例如 `TOMCAT_PORT`、`H2_PORT`（預設 `3306`，可用 `JsPlus_H2_PORT`
覆寫），以及應用程式使用的各種 `Custom_*` / `Env_*` 金鑰。

### `JsPlus/config/DataSource.properties`

主資料庫的 H2 連線池（`jdbc:h2:tcp://localhost:${H2_PORT}/tomcatdb`，
使用者 `sample_user`，預設密碼於 `dbscript/change.sql` 中設定）。

### SSL / TLS

- 開發用憑證隨附於 `JsPlus/key/`（`keystore.jks`、`truststore.jks`）。
- 可於執行期間新增額外的依主機（per-host）憑證：
  `addSslHost(hostname, keystoreFile, keystorePass, keyAlias)`，並可透過
  `reloadSslHostConfig(hostName)` 重新載入——無需重啟伺服器。
- 可選的 mTLS：透過 `clientAuth`、`truststoreFile`、`truststorePass`、`truststoreType`。
- **DB-backed SSL hosts（以資料庫支援的 SSL 主機）** — 從 JS 主控台透過 `domain_ssl_host` 資料表
  管理主機（`tomcat.createDomainSslHost(...)`、`updateDomainSslHost`、`removeDomainSslHost`、
  `queryDomainSslHost(s)`、`tomcat.loadDomainSslHosts()`）。已持久化的記錄啟動時自動套用。
  每列記錄的 `keystore_pass` 均以各自獨立的 `encrypt_key`（每列 AES 金鑰）加密存儲，
  做法與 DomainAuth 一致；舊有明文記錄仍可正常運作，並於下次更新時重新加密。
  僅 `tomcat.addSslHost(...)` 可即時套用至執行中的 connector（並一併持久化）；
  `updateDomainSslHost` 與 `removeDomainSslHost` 只改資料庫——需重啟 Tomcat 先生效。

---

## 資料庫（H2）

在主資料庫套用 `dbscript/change.sql` 以建立：

| 資料表 | 用途 |
|---|---|
| `domain_auth` | 依 `domain:port` 的驗證開關 + AES `encrypt_key` |
| `domain_auth_user` | 使用者名稱與 AES 加密密碼 |
| `domain_mapping_param` | 網域 → 代理目標參數記錄 |
| `domain_ssl_host` | 額外的 SSL 主機憑證記錄（`keystore_pass` 以每列獨立金鑰作 AES 加密） |
| `domain_ip_rule` | 依 `domain:port` 的 IP 規則（每行一條：ALLOW/DENY + 規則樣式） |
| `ip_request_log` | 依 IP 的請求記錄（批次寫入，見 *IP 攻擊防禦*） |
| `ip_defense_rule` | 攻擊防禦規則（門檻；可選 URI 正則；`"0","0"` = 全伺服器） |
| `ip_ban` | 防禦引擎自動產生的封禁記錄（含到期時間） |

`ghost_remote_command` 存放 ghost 主控台使用的遠端指令佇列記錄。
`dbscript/fallback.sql` 會刪除上表所列的全部資料表。

---

## 網域代理與安全（Domain Proxy & Security）

### 反向代理（`DomainProxy`）

當 `DomainProxy.enable=true` 時，每個請求都會進入代理 servlet。目標會依 `domain:port`
從下列來源解析：

1. H2 的 `domain_mapping_param` 記錄（啟動時載入），或
2. `JsPlus/domain/<domain>_<port>.js` 的按網域 JS 腳本，其暴露 `DomainMapping.getParams()`：

```js
DomainMapping.getParams = function(){
    return {
        target: 'https://killcoding.com',   // 後端目標
        connectTimeout: '60s',
        requestTimeout: '30s',              // 整個請求時限；可省略（用 DomainProxy.proxyRequestTimeout）
        forwardedHeaders: { header_A: 'value_1' },
        responseHeaders:  { header_1: 'value_1' },
        forwardedHeader: true,
        filterEnable: true
    };
};
```

`DomainMapping.filter()` 掛鉤可在回傳前改寫上游回應（具 gzip 感知）。

亦可從 JS 主控台管理網域對應——`tomcat.setDomainMapping(domain, port, params)`、
`tomcat.removeDomainMapping(domain, port)` 與 `tomcat.queryDomainMapping(s)`——
會持久化至 `domain_mapping_param`，並立即套用至代理快取。

**示例 — 一行指令為 `domain:port` 設定路由：**

```js
// 將所有指向 sample.com:8080 的請求轉發至後端應用程式 http://127.0.0.1:8081
tomcat.setDomainMapping("sample.com", "8080", {
    target:       "http://127.0.0.1:8081",      // 後端基礎 URL（必需）
    scriptEnable: true,                         // 執行按網域 JS 腳本
    scriptFile:   "sample.com.js" // 按網域參數取自該檔案
});
// 主控台輸出：DomainMapping sample.com:8080 created.

// 查詢確認設定已生效
tomcat.queryDomainMapping("sample.com", "8080");
// 主控台輸出：DomainMapping sample.com:8080 ...（id、domain、port、paramValue、時間戳）
```

`target` 必須為有效的後端基礎 URL；缺少時代理會回應 `502 Target domain not found`。
省略 `scriptFile` 時，會回退至按 `domain:port` 匹配的按網域腳本（見 `JsPlus/domain/`）。

### Basic Auth（`SecurityFilter`）

從 JS 主控台（暴露於 `TomcatEmbed`）為網域啟用驗證並建立使用者：

```js
tomcat.createDomainAuth('127.0.0.1', '8443', true);
tomcat.createDomainAuthUser('127.0.0.1', '8443', 'Sample', 'password');
```

管理 API：`updateDomainAuth`、`removeDomainAuth`、`queryDomainAuth(s)`、
`createDomainAuthUser`、`updateDomainAuthUser`、`removeDomainAuthUser`、`queryDomainAuthUser(s)`。
密碼以 AES 加密儲存，並以按網域的 `encrypt_key` 進行驗證。

### IP 存取控制（`IpFilter`）

以 `domain_ip_rule` 資料表為後端、依 `domain:port` 的允許／拒絕規則，可從 JS 主控台管理。
**每行即為一條規則**：一個 `ruleType`（`ALLOW`／`DENY`）與單一 `ipPattern`；格式支援
**精確 IP**（`192.168.1.10`）、**CIDR 網段**（`192.168.1.0/24`）與**萬用字元**（`192.168.1.*`）。
於 `tomcat.properties` 設定 `IpFilter.enable=false` 可關閉整個過濾器（所有請求直接放行）；
過濾器預設為啟用。

每個請求的評估次序：

1. **允許優先（allow wins）** — 命中任何 `ALLOW` 規則（按網域或 `0:0` 後備）即放行，即使同時命中拒絕規則。
2. **拒絕** — 命中 `DENY` 規則的請求回應 `403`。
3. **白名單模式** — 設有允許規則的網域，不在任何允許規則上的 IP 回應 `403`。
4. **無規則** — 其餘請求全部放行，交由 `SecurityFilter`／webapp 處理。

**全伺服器後備規則（`0:0`）** — 與 `SecurityFilter` 相同，`domain = "0"` 且 `port = "0"` 的記錄
即為全域預設：套用至所有沒有自有規則的 `domain:port`。精確的 `domain:port` 規則永遠優先於
`0:0` 後備規則。

```js
// 全伺服器預設：封鎖所有人，再於所有網域放行辦公室網段
tomcat.createDomainIpRule("0", "0", "DENY", "0.0.0.0/0");
tomcat.createDomainIpRule("0", "0", "ALLOW", "192.168.1.0/24");
```

```js
// 允許辦公室網段，再封鎖單一異常來源——每行即一條規則
tomcat.createDomainIpRule("sample.com", "8080", "ALLOW", "192.168.1.0/24");
tomcat.createDomainIpRule("sample.com", "8080", "ALLOW", "10.10.0.*");
tomcat.createDomainIpRule("sample.com", "8080", "DENY", "203.0.113.7");
// 主控台輸出：DomainIpRule sample.com:8080 [ALLOW] 192.168.1.0/24 created.

// 白名單模式——管理埠只允許 127.0.0.1
tomcat.createDomainIpRule("sample.com", "8443", "ALLOW", "127.0.0.1");

// 修正規則樣式，然後查詢確認
tomcat.updateDomainIpRule("sample.com", "8080", "ALLOW", "10.10.0.*", "10.20.0.*");
tomcat.queryDomainIpRule("sample.com", "8080", "DENY", "203.0.113.7");
tomcat.queryDomainIpRules();
```

變更即時生效（每次寫入都會使按網域的快取失效，下一個請求重新讀取資料庫）。
`tomcat.loadDomainIpRules()` 會清空整個快取，強制立即重新讀取，無需等待快取到期。

### IP 攻擊防禦（`IpAttackDefense`）

將每個請求記錄至 `ip_request_log`，並可於 IP 超出設定門檻時自動封禁。記錄採用
**非同步批次寫入**——每個請求先推入 `CacheArray` 佇列，再由背景 `CacheArrayFilter`
執行緒以 `CacheDriverExecutor.executeBatch` 批次寫入資料庫（與 `ServerLog` 相同模式），
熱路徑（hot path）不會阻塞於 JDBC 寫入。

**規則匹配方式** — 每條規則皆按 `domain:port` 匹配；`"0"`／`"0"` 即全伺服器全域
（與 `IpFilter`／`SecurityFilter` 相同之 `0:0` 慣例）。當 `uriPattern` 有值時，
請求 URI 亦須符合該正則（`Pattern.matcher().find()`）；當其為空白時，
所匹配 `domain:port` 上的所有 URI 皆計數。

**封禁機制** — 對每條啟用規則，引擎於共用 `Cache` 中維護每個 IP 的滑動時間窗計數器
（時間窗 = `window_seconds`）。當 IP 計數達到 `threshold`，引擎會：將 `DENY` 記錄寫入
`domain_ip_rule`（令 `IpFilter` 立即封鎖該 IP）、於 `ip_ban` 記錄封禁及其 `expire_time`，
並於封禁到期時自動移除規則並軟刪除該筆封禁記錄。防禦於過濾器鏈中在 `IpFilter` 之前執行；
防禦或資料庫失敗不會阻礙請求處理。

**流程**

```
請求 ──► IpAttackDefense.doFilter
   │
   ├─ 1. record()  ──► 推入 CacheArray 佇列 ──► 背景執行緒批次寫入 ip_request_log（每 logTimer 毫秒）
   │
   └─ 2. checkDefense() ──► 載入啟用規則（快取 ruleCacheSeconds 秒）
          │
          └─ 每個 IP 的滑動時間窗計數器（key = 規則 ID + IP，時間窗 = window_seconds）
                 │
                 ├─ 計數 < threshold ──────────► 放行
                 └─ 計數 ≥ threshold ──► ban()
                        ├─ 寫入 DENY 至 domain_ip_rule（標記 ip_ban_id）──► IpFilter 立即封鎖
                        ├─ 寫入 ip_ban（expire_time = 現在 + ban_seconds）
                        └─ 後續請求 ──► IpFilter ──► 403 拒絕存取

背景掃描（每 ≥ 5 秒）：expireBans()
   └─ ip_ban.expire_time < 現在 ──► 軟刪除封禁記錄 ──► 刪除對應的 DENY 規則
          ──► 自動解封
```

```js
// 全伺服器：整個伺服器任何 IP 超過每分鐘 120 次請求 → 封禁 600 秒
tomcat.createDefenseRule("burst-limit", "0", "0", "", 60, 120, 600, true);

// 按網域：sample.com:8080 超過每分鐘 30 次 → 封禁 300 秒
tomcat.createDefenseRule("login-burst", "sample.com", "8080", "", 60, 30, 300, true);

// 按網域 + URI：每分鐘超過 10 次命中 /api/login → 封禁 3600 秒
tomcat.createDefenseRule("api-login-burst", "sample.com", "8080", "^/api/login", 60, 10, 3600, true);

// 管理規則與封禁
tomcat.queryDefenseRules();
tomcat.updateDefenseRule("defenseRuleId", "burst-limit", "0", "0", "", 60, 120, 600, true);
tomcat.removeDefenseRule("defenseRuleId");

tomcat.queryIpBans();
tomcat.removeIpBan("0", "0", "203.0.113.7");      // 立即手動解除封禁

// 檢視／清除請求記錄（亦可直接以 SQL 查詢）
tomcat.queryIpRequestLogs();
tomcat.clearIpRequestLogs();
```

---

## TomcatEmbed.js API 參考

所有管理操作皆為 `JsPlus/script/TomcatEmbed.js` 所暴露之 `tomcat` 物件上的方法。
每個方法會將結果（`resMap.msg`）輸出至主控台，並以字串形式回傳。

### 生命週期

| 方法 | 說明 |
|---|---|
| `tomcat.start()` | 載入 `JsPlus/config/tomcat.properties` 並啟動內嵌 Tomcat |
| `tomcat.stop()` | 停止內嵌 Tomcat |
| `tomcat.restart()` | 先 `stop()` 再 `start()` |

### 按網域 Basic Auth（`SecurityFilter`）

| 方法 | 說明 |
|---|---|
| `tomcat.createDomainAuth(domain, port, authEnable)` | 啟用／停用某 `domain:port` 的 Basic Auth |
| `tomcat.updateDomainAuth(domain, port, authEnable)` | 更新某 `domain:port` 的驗證開關 |
| `tomcat.removeDomainAuth(domain, port)` | 移除某 `domain:port` 的驗證設定 |
| `tomcat.queryDomainAuth(domain, port)` | 查詢單一驗證設定 |
| `tomcat.queryDomainAuths()` | 查詢全部驗證設定 |
| `tomcat.createDomainAuthUser(domain, port, username, plaintextPass)` | 建立使用者（密碼以 AES 加密） |
| `tomcat.updateDomainAuthUser(domain, port, username, plaintextPass)` | 更新使用者密碼 |
| `tomcat.removeDomainAuthUser(domain, port, username)` | 移除使用者 |

### 網域對應（`DomainProxy`）

| 方法 | 說明 |
|---|---|
| `tomcat.setDomainMapping(domain, port, domainParams)` | 建立或更新 `domain:port` 對應（見上方示例） |
| `tomcat.removeDomainMapping(domain, port)` | 移除對應 |
| `tomcat.queryDomainMapping(domain, port)` | 查詢單一對應 |
| `tomcat.queryDomainMappings()` | 查詢全部對應 |

### SSL 主機（`domain_ssl_host`）

| 方法 | 說明 |
|---|---|
| `tomcat.createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 持久化新的 SSL 主機記錄 |
| `tomcat.updateDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 僅更新資料庫記錄（需重啟 Tomcat 先生效） |
| `tomcat.removeDomainSslHost(hostname)` | 軟刪除記錄 |
| `tomcat.queryDomainSslHost(hostname)` | 查詢單一 SSL 主機 |
| `tomcat.queryDomainSslHosts()` | 查詢全部 SSL 主機 |
| `tomcat.addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 套用至執行中的伺服器（新主機時一併持久化） |
| `tomcat.loadDomainSslHosts()` | 由資料庫重新載入全部 SSL 主機並套用至執行中的伺服器 |

### IP 存取規則（`domain_ip_rule`）

| 方法 | 說明 |
|---|---|
| `tomcat.createDomainIpRule(domain, port, ruleType, ipPattern)` | 新增一條**手動**規則（`ruleType` = `ALLOW`／`DENY`）；由 `ip_ban` 寫入的記錄不會參與重複檢查 |
| `tomcat.updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)` | 變更某一條**手動**規則的樣式（自動封禁記錄絕不觸碰） |
| `tomcat.removeDomainIpRule(domain, port, ruleType, ipPattern)` | 軟刪除某一條**手動**規則——解除自動封禁請用 `tomcat.removeIpBan(...)` |
| `tomcat.queryDomainIpRule(domain, port, ruleType, ipPattern)` | 查詢單一規則，顯示 `Source`（`manual`／`ip_ban`）及 `IpBanId` |
| `tomcat.queryDomainIpRules()` | 查詢全部規則；每行標示 `(manual)` 或 `(ip_ban)` |
| `tomcat.loadDomainIpRules()` | 清空規則快取，使下一個請求重新讀取資料庫 |

### IP 攻擊防禦（`ip_defense_rule`／`ip_ban`／`ip_request_log`）

| 方法 | 說明 |
|---|---|
| `tomcat.createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | 新增防禦規則（`"0","0"` = 全伺服器；`uriPattern` 為可選 URI 正則） |
| `tomcat.updateDefenseRule(ruleId, ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | 更新規則 |
| `tomcat.removeDefenseRule(ruleId)` | 軟刪除規則 |
| `tomcat.queryDefenseRules()` | 查詢全部規則 |
| `tomcat.queryIpBans()` | 查詢全部自動封禁 |
| `tomcat.removeIpBan(domain, port, ip)` | 立即解除封禁（同時刪除對應的 DENY 規則） |
| `tomcat.queryIpRequestLogs()` | 查詢最近 100 筆請求記錄 |
| `tomcat.clearIpRequestLogs()` | 刪除全部請求記錄 |

---

## Web 日誌（`ServerLog`）

`tomcat.properties` 中的 `WebLog.*` 金鑰：

| 金鑰 | 預設值 | 說明 |
|---|---|---|
| `WebLog.Enable` | `true` | 總開關 |
| `WebLog.Folder` | `${JS_PLUS_PATH}/logs/domain` | CSV 輸出目錄 |
| `WebLog.MaxFileSize` | `10MB` | 輪替大小 |
| `WebLog.MaxBackupIndex` | `10` | 保留的輪替檔案數 |
| `WebLog.ArchiveDays` | `31` | 刪除超過 N 天的日誌 |
| `WebLog.ParamMaxLength` | `20` | 日誌中參數/標頭的截斷長度 |
| `WebLog.Allowed` / `WebLog.Ignored` | — | 過濾記錄內容的 regex 清單 |
| `WebLog.Type` | — | 要記錄的類型（逗號分隔） |

日誌會非同步批次寫入（預設 1 秒計時器）至每日 CSV 檔案。

---

## 命令列參數

shell/bat 啟動器會將命令列選項傳遞至 JsPlus 主類別（`com.killcoding.jsplus.Main`），例如：

```sh
-ScriptEngine graalvm.js    # JS 引擎（可退回 nashorn）
-Env script                 # 執行環境
-WriteHistory false         # 停用主控台歷史
-GhostName H2Ghost          # ghost 主控台名稱
-Task script/StartTomcat.js # 啟動腳本
```

選項使用 `-key value`（或布林 `--flag`）形式。在本專案內，伺服器啟動參數是從
`JsPlus/config/tomcat.properties` 透過 `TomcatServer.loadTomcatProps(...)` 取得，
而非命令列解析。

---

## 自動化測試規格（Automated Testing）

### 自動化測試腳本（主控台模式）

五套可執行的測試套件位於 `test/`（存放庫根目錄），
於執行中的 JsPlus 主控台內執行——無需第三方測試框架，亦無需啟動器腳本：

| 腳本 | 涵蓋範圍 |
|---|---|
| `TestBase.js` | 共用測試基礎（Java 繫結、`check`／`isOk`／`msg`、`callTomcat`、`runCmd`／`curlStatus`、`dbVerify` SQL 驗證、HTML／MD／CI 摘要報告寫入） |
| `TestAll.js` | 統一入口——依序執行五套測試套件，並輸出總計通過／失敗摘要 |
| `TestDomainProxy.js` | 21 個案例——網域對應 CRUD ＋ 透過 `curl` 進行即時反向代理請求驗證（含 `mode=internal` 分派及組合驗證） |
| `TestSecurityFilter.js` | 16 個案例——Basic Auth CRUD（`domain_auth`／`domain_auth_user`）＋ 透過 `curl` 進行即時 `401`／`200` 驗證 |
| `TestIpFilter.js` | 20 個案例——網域 IP 規則 CRUD（`domain_ip_rule`，ALLOW／DENY，含 `uri` 前綴及 ALLOW 優先於 DENY）＋ 透過 `curl` 進行即時 `403`／`200` 驗證 |
| `TestIpAttackDefense.js` | 16 個案例——防禦規則 CRUD（`ip_defense_rule`／`ip_ban`／`ip_request_log`）＋ 即時自動封禁（`403`）流程驗證 |
| `TestTomcatServer.js` | 34 個案例——經 `tomcat` 主控台入口（`TomcatEmbed.js`）執行 TomcatServer 行為測試：`addSslHost`＋清理、`Webapp.contextPath`／`Webapp.appBase` 組態契約（含舊鍵回歸防護）、`SSL.*` 區塊、keystore 就緒狀態（JKS＋alias＋信任錨）、唯讀行為冒煙測試（`getTomcat`／`queryDomainSslHosts`／`loadDomainSslHosts`）、DomainSslHost CRUD 及 HTTP/2 契約（`Http2.enable=true` 組態＋純文字連接埠即時 h2c 握手）——每次 API 呼叫均記錄 `{code, msg}` 返回值，DB 寫入附 `dbVerify`（`SELECT *`）驗證＋清理 |

執行全部測試的一鍵流程（停止服務 → DB 初始化 → H2 → 內嵌 Tomcat →
108 項檢查 → `test_reports/`）：

- Windows：`test\TestAll.bat`
- Linux：`test/TestAll.sh`

或：H2 與 Tomcat 已啟動（例如先執行 `StartH2`，再執行 `StartTomcat`）時，
可由 JS 主控台直接執行全部測試：

```js
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestAll.js');
/
```

或執行單一套件：

```js
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestDomainProxy.js');
/
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestSecurityFilter.js');
/
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestIpFilter.js');
/
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestIpAttackDefense.js');
/
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestTomcatServer.js');
/
```

前置條件（僅 JS 主控台執行需要；`TestAll.bat`／`TestAll.sh` 會自動處理一切）：

- 先啟動服務：`StartH2.bat/sh`，再執行 `StartTomcat.bat/sh`。
- 執行 SecurityFilter 測試套件前，請於 `JsPlus/config/tomcat.properties` 設定
  `SecurityFilter.cacheSeconds=0` 並重新啟動 Tomcat——否則預設 60 秒的
  auth-enable 快取會令即時檢查讀取到過期狀態。
- 執行 IpFilter 測試套件前，請於 `JsPlus/config/tomcat.properties` 設定
  `IpFilter.cacheSeconds=0` 並重新啟動 Tomcat——即時 `403`／`200` 檢查
  需無條件讀取規則列表。
- 執行 IpAttackDefense 測試套件前，請設定 `IpFilter.cacheSeconds=0` 及
  `IpAttackDefense.ruleCacheSeconds=0` 並重新啟動 Tomcat——即時自動封禁
  流程（防禦規則＋自動寫入的 `ip_ban` DENY 規則）需立即生效。

每項檢查會輸出 `[PASS]`／`[FAIL]`，並將以顏色標示的 HTML 報告
（PASS 綠色／FAIL 紅色，包含 Case Id／Test Objective／Result／Detail）寫入
`test_reports/TestDomainProxy-Report.html`、
`test_reports/TestSecurityFilter-Report.html`、
`test_reports/TestIpFilter-Report.html`、
`test_reports/TestIpAttackDefense-Report.html` 及
`test_reports/TestTomcatServer-Report.html`。

每個非查詢案例會附加 `dbVerify` 區塊：以黃色小字顯示所執行的 `SELECT`
語句，並將結果列以 HTML 表格（含 `row num` 欄）呈現，方便測試人員直接
核對資料表實際狀態（透過項目的 `CacheDriverExecutor.find` 執行——與
API 使用相同的資料存取路徑）。每個套件結尾均有清理案例，移除該套件其餘
測試數據（例如前置網域對應），並驗證相關資料表已清空
（`deleted=FALSE` → 0 列），令重跑及 `TestAll.js` 合併執行時不會互相污染。

### 持續整合（Continuous Integration，GitHub Actions）

`.github/workflows/ci.yml` 於每次 push／pull request 至 `main`／`master`
時執行完整測試套件：

1. 簽出存放庫（Check out）。
2. 設定 Oracle GraalVM 25（以 `$JAVA_HOME` 作為後備 Java）。
3. 安裝 Ant 並建置 `LibHome/jsplus/default/jsplus-tomcat.jar`（`ant build`）。
4. 執行 `test/TestAll.sh`——與本地完全相同的一鍵流程。
5. 上傳 `test_reports/` 及 `logs/` 為 `test-reports` 產物（可於 Actions 執行頁面下載）。

注意：`Java/`（內嵌 JDK）已加入 `.gitignore`，CI runner 依賴系統 JDK；
`LibHome/`（全部執行時期 JAR）會提交至存放庫。

---

## 授權（License）

以 **MIT 授權（MIT License）** 釋出。完整條文見 [LICENSE](LICENSE)。
