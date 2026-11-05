# JsPlus-Tomcat 操作手冊

## 目錄
1. [項目簡介](#1-項目簡介)
2. [環境要求](#2-環境要求)
3. [建置與啟動](#3-建置與啟動)
4. [配置說明](#4-配置說明)
5. [功能模組](#5-功能模組)
6. [JS Console API（TomcatEmbed.js）](#6-js-console-apitomcatembedjs)
7. [測試](#7-測試)
8. [Release 流程](#8-release-流程)
9. [除錯與日誌](#9-除錯與日誌)

---

## 1. 項目簡介

JsPlus-Tomcat 係一個輕量級嵌入式 Tomcat 反向代理與安全防禦伺服器，基於 JsPlus（GraalVM JS）平台運行。主要功能：

- 嵌入式 Tomcat 伺服器（HTTP/HTTPS/HTTP2）
- IP 過濾器（allow/deny 規則）
- IP 攻擊防禦（自動封禁異常 IP）
- Security Filter（請求安全檢查）
- Domain Proxy（反向代理 / 內部轉發）
- 自訂錯誤頁（CustomErrorReportValve）
- H2 嵌入式數據庫
- Script Console（JS 控制台）

---

## 2. 環境要求

| 項目 | 要求 |
|---|---|
| Java | Oracle GraalVM 25+ |
| Ant | 1.10+ |
| 操作系統 | Windows / Linux / macOS |
| 記憶體 | 最低 512MB |
| 磁碟空間 | 500MB |

---

## 3. 建置與啟動

### 3.1 建置

```bash
# Windows
ant.bat

# Linux / macOS
ant.sh
```

建置完成後，jar 會輸出到：
```
LibHome/jsplus/default/jsplus-tomcat-<version>.jar
```

### 3.2 啟動

```bash
# Windows
JsPlus/bin/StartTomcat.bat

# Linux / macOS
JsPlus/bin/StartTomcat.sh
```

### 3.3 停止

```bash
# Windows
JsPlus/bin/StopAll.bat

# Linux / macOS
JsPlus/bin/StopAll.sh
```

> **注意**：強制殺 java process 後，必須先啟動 H2 ghost 再啟動 Tomcat，否則會 H2 connection refused。

---

## 4. 配置說明

配置文件：`JsPlus/config/Tomcat.properties`

### 4.1 基本連接

```properties
port=8080
sslPort=8443
```

### 4.2 SSL

```properties
SSL.enable=true
SSL.keyRoot=${JS_PLUS_PATH}/key/
SSL.keystoreFile[0]=default_keystore.jks
SSL.keystorePass[0]=changeit
SSL.keyAlias[0]=client
SSL.truststoreFile=default_truststore.jks
SSL.truststorePass=changeit
SSL.truststoreType=JKS
```

### 4.3 HTTP/2

```properties
Http2.enable=false
```

### 4.4 IP Filter

```properties
IpFilter.enable=true
IpFilter.cacheSeconds=0
```

### 4.5 IP Attack Defense

```properties
IpAttackDefense.enable=true
IpAttackDefense.logTimer=5000
IpAttackDefense.cacheSeconds=0
IpAttackDefense.logRetention=1h
IpAttackDefense.logPhysicalDelete=2h
```

### 4.6 Security Filter

```properties
SecurityFilter.cacheSeconds=0
```

### 4.7 Domain Proxy

```properties
DomainProxy.enable=true
DomainProxy.proxyConnectTimeout=60s
DomainProxy.proxyRequestTimeout=30s
```

### 4.8 Script Console

```properties
TomcatScriptConsole.poolSize=100
TomcatScriptConsole.cacheSeconds=2
TomcatScriptConsole.freedDelayMs=10
TomcatScriptConsole.engineName=graalvm.js
```

### 4.9 Webapp

```properties
Webapp.appBase[0]=${JS_PLUS_PATH}/../Webapps/default/
Webapp.appBase[1]=${JS_PLUS_PATH}/../Webapps/default/
Webapp.appBase[2]=${JS_PLUS_PATH}/../Webapps/error/
```

### 4.10 錯誤頁

```properties
errorRedirectEnable=true
errorRedirectUrl=https://testerror.com:8443/error/{ErrorCode}.html?RequestUrl={RequestUrl}
```

---

## 5. 功能模組

### 5.1 IpFilter

IP 過濾器，支援 allow/deny 規則。規則存儲於 H2 數據庫。

```javascript
// 範例：查詢規則
tomcat.listIpFilterRules();

// 新增規則
tomcat.addIpFilterRule({ip: "192.168.1.1", action: "allow"});
```

### 5.2 IpAttackDefense

自動偵測並封禁異常 IP。可配置日誌保留時間與物理刪除時間。

### 5.3 SecurityFilter

請求安全檢查，過濾惡意請求。

### 5.4 DomainProxy

反向代理 / 內部轉發。支援兩種模式：

- **proxy**：外部反向代理，轉發到目標 URL
- **internal**：內部 forward，轉發到本地 webapp

```javascript
// 範例：設定 domain mapping
tomcat.setDomainMapping('example.com', '443', {
  target: 'https://127.0.0.1:8443/default',
  scriptEnable: false
});
```

### 5.5 CustomErrorReportValve

自訂錯誤頁，直接輸出 HTML（白底、居中、系統字體、Instagram 藍連結）。無需依賴 web.xml 或 /error/ webapp。

---

## 6. JS Console API（TomcatEmbed.js）

經由 GraalVM JS 控制台（Script Console）呼叫，所有方法都掛喺全域 `tomcat` 物件上。

### 6.1 Server 控制

| 方法 | 說明 |
|---|---|
| `tomcat.start()` | 啟動 Tomcat server |
| `tomcat.stop()` | 停止 Tomcat server |
| `tomcat.restart()` | 重啟（stop + start） |
| `tomcat.getVersion()` | 取得版本號 |
| `tomcat.getTomcatHome()` | 取得 Tomcat home 路徑 |
| `tomcat.getTomcat()` | 取得底層 Tomcat 物件 |
| `tomcat.getMemoryInfo()` | 取得記憶體資訊 |
| `tomcat.loadParams(params)` | 載入參數 Map |
| `tomcat.loadTomcatProps(params)` | 從 Tomcat.properties 載入參數 |
| `tomcat.reloadSslHostConfig(hostName)` | 重載 SSL host config（唔傳參數則全部） |

### 6.2 Domain Auth（SecurityFilter）

| 方法 | 說明 |
|---|---|
| `tomcat.createDomainAuth(domain, port, authEnable)` | 建立 domain auth 設定 |
| `tomcat.updateDomainAuth(domain, port, authEnable)` | 更新 domain auth 設定 |
| `tomcat.removeDomainAuth(domain, port)` | 移除 domain auth 設定 |
| `tomcat.queryDomainAuth(domain, port)` | 查詢單一 domain auth |
| `tomcat.queryDomainAuths()` | 查詢全部 domain auth |
| `tomcat.createDomainAuthUser(domain, port, username, plaintextPass)` | 建立 auth 使用者 |
| `tomcat.updateDomainAuthUser(domain, port, username, plaintextPass)` | 更新 auth 使用者密碼 |
| `tomcat.removeDomainAuthUser(domain, port, username)` | 移除 auth 使用者 |
| `tomcat.queryDomainAuthUser(domain, port, username)` | 查詢單一 auth 使用者 |
| `tomcat.queryDomainAuthUsers(domain, port)` | 查詢某 domain 全部使用者 |
| `tomcat.setAuthLockPolicy(domain, port, maxAttempts, lockMinutes)` | 設定帳號鎖定策略 |
| `tomcat.unlockAuthUser(domain, port, username)` | 解鎖被鎖定嘅使用者 |

### 6.3 Domain Mapping（DomainProxy）

| 方法 | 說明 |
|---|---|
| `tomcat.setDomainMapping(domain, port, params)` | 設定 catch-all mapping（uri ""） |
| `tomcat.setDomainMapping(domain, port, uri, params)` | 設定 uri 前綴特定 mapping |
| `tomcat.removeDomainMapping(domain, port)` | 移除 catch-all mapping |
| `tomcat.removeDomainMapping(domain, port, uri)` | 移除 uri 特定 mapping |
| `tomcat.queryDomainMapping(domain, port)` | 查詢 catch-all mapping |
| `tomcat.queryDomainMapping(domain, port, uri)` | 查詢 uri 特定 mapping |
| `tomcat.queryDomainMappings()` | 查詢全部 mapping |

### 6.4 Domain SSL Host

| 方法 | 說明 |
|---|---|
| `tomcat.createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 建立 SSL host 設定（持久化到 DB） |
| `tomcat.updateDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 更新 SSL host 設定 |
| `tomcat.removeDomainSslHost(hostname)` | 移除 SSL host 設定 |
| `tomcat.queryDomainSslHost(hostname)` | 查詢單一 SSL host |
| `tomcat.queryDomainSslHosts()` | 查詢全部 SSL host |
| `tomcat.addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | 動態加入 SSL host（運行中） |
| `tomcat.loadDomainSslHosts()` | 從 DB 載入全部 SSL host |

### 6.5 IpFilter

| 方法 | 說明 |
|---|---|
| `tomcat.createDomainIpRule(domain, port, ruleType, ipPattern)` | 建立 catch-all IP 規則（ALLOW/DENY） |
| `tomcat.createDomainIpRule(domain, port, uri, ruleType, ipPattern)` | 建立 uri 範圍 IP 規則 |
| `tomcat.updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)` | 更新 catch-all IP 規則 |
| `tomcat.updateDomainIpRule(domain, port, uri, ruleType, oldIpPattern, newIpPattern)` | 更新 uri 範圍 IP 規則 |
| `tomcat.removeDomainIpRule(domain, port, ruleType, ipPattern)` | 移除 catch-all IP 規則 |
| `tomcat.removeDomainIpRule(domain, port, uri, ruleType, ipPattern)` | 移除 uri 範圍 IP 規則 |
| `tomcat.queryDomainIpRule(domain, port, ruleType, ipPattern)` | 查詢單一 IP 規則 |
| `tomcat.queryDomainIpRules()` | 查詢全部 IP 規則 |
| `tomcat.loadDomainIpRules()` | 從 DB 載入全部 IP 規則 |

### 6.6 IpAttackDefense

| 方法 | 說明 |
|---|---|
| `tomcat.createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | 建立防禦規則 |
| `tomcat.updateDefenseRule(ruleId, ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | 更新防禦規則 |
| `tomcat.removeDefenseRule(ruleId)` | 移除防禦規則 |
| `tomcat.queryDefenseRules()` | 查詢全部防禦規則 |
| `tomcat.queryIpBans()` | 查詢全部被封鎖嘅 IP |
| `tomcat.removeIpBan(domain, port, ip)` | 解除 IP 封鎖 |
| `tomcat.queryIpRequestLogs()` | 查詢 IP 請求日誌 |
| `tomcat.clearIpRequestLogs()` | 清空 IP 請求日誌 |

---

## 7. 測試

### 6.1 全量測試

```bash
# Windows
test/TestAll.bat

# Linux / macOS
test/TestAll.sh
```

### 6.2 測試流程

1. StopAll（停止所有 java process）
2. 清除 `JsPlus/.tmp/*` 同 `logs/*`
3. ant.bat 建置
4. 執行對應模組測試
5. TestAll 全量測試

### 6.3 測試報告

測試完成後，報告輸出到：
```
docs/latest/report/TestAll-Report.html
```

---

## 8. Release 流程

### 8.1 建置

```bash
ant.bat
```

### 8.2 Release

```bash
release.bat
```

Release 會掃描同級目錄下所有 project，將新 jar 複製到已持有 `jsplus-tomcat-*.jar` 嘅 project 嘅 `LibHome/jsplus/default/`。

舊 jar 會被重新命名為 `*.jar.bak`（唔會刪除）。

---

## 9. 除錯與日誌

### 9.1 日誌位置

```
JsPlus/logs/
```

### 9.2 常見問題

#### H2 connection refused

**原因**：強制殺 java process 後，H2 ghost process 都被殺。

**解決**：先啟動 `StartH2.bat`，再啟動 `StartTomcat.bat`。

#### 404 Not Found

**原因**：Domain mapping 指向唔存在嘅 host 或路徑。

**解決**：檢查 `tomcat.queryDomainMapping(domain, port)` 嘅結果。

#### 錯誤頁唔係自訂風格

**原因**：CustomErrorReportValve 未正確註冊。

**解決**：確認 `TomcatServer.java` 有呼叫 `setErrorReportValveClass(CustomErrorReportValve.class.getName())`。

---

## 附錄：版本歷史

| 版本 | 日期 | 備註 |
|---|---|---|
| 8.0.103 | 2026-11-04 | CustomErrorReportValve + release.bat 對齊 |
