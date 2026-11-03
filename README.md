# JsPlus-Tomcat

**A lightweight, Java-native Nginx alternative.** A reverse proxy, web server and per-domain gateway built on embedded **Apache Tomcat 11**: per-domain routing, Basic Auth, IP allow/deny, attack defense and TLS are live rows in an embedded H2 database and are managed as one-line calls from a GraalVM JS console. No XML server config, no plugin tier, no external install — the whole stack (Tomcat + H2 + JS runtime + webapps) boots from a single folder in one JVM.

## Main features

| Feature | Description | Class |
| --- | --- | --- |
| **Reverse proxy** | Route a `domain:port` to a backend target; hop-by-hop headers stripped, forwarded/upstream headers configurable; per-domain JS `filter()` (response rewrite) and `preRequest()` (cache/stub short-circuit) hooks | `DomainProxy` |
| **Per-domain Basic Auth** | Auth switch and user list per `domain:port`, AES-encrypted passwords, cached lookups, account lockout after consecutive failures | `SecurityFilter` |
| **IP access control** | One ALLOW/DENY rule per row per `domain:port`; exact IP, CIDR and wildcard patterns; server-wide `0:0` fallback; allow wins over deny | `IpFilter` |
| **IP attack defense** | Batched request logging plus sliding-window rules that auto-ban offending IPs into `domain_ip_rule` and lift the ban on expiry | `IpAttackDefense` |
| **Embedded Tomcat** | Programmatic start / stop / restart of Tomcat 11; multi-webapp deployment; connector and resource tuning | `TomcatServer` |
| **HTTP/2** | `h2` over TLS (ALPN on the SSL connector) and `h2c` on the plain connector (HTTP/1.1 Upgrade or prior knowledge) | `TomcatServer` |
| **Hot-reloadable SSL** | Add or reload a host certificate on the running connector; DB-backed host records are re-applied at every boot | `TomcatServer` |
| **Script console pool** | Bounded pool of GraalVM JS consoles that per-request script execution draws from, with usage metrics | `TomcatScriptConsole` |
| **Web access log** | Asynchronous batched CSV logging with size-based rotation, archiving and URI allow/ignore rules | `ServerLog` |
| **Database-driven configuration** | Auth, mapping, SSL, IP and defense settings are living table rows; edits apply immediately and are restored at startup | H2 `tomcatdb` |

## Runtime environment

### Java

Requires **GraalVM 25 (Java 25)**, placed at `Java\default` (not tracked by Git; launchers fall back to the system `JAVA_HOME` when missing):

| Directory | Purpose |
| --- | --- |
| `Java\default` | Full JDK for build and startup (includes `jmods`) |

### Ant (optional)

`Ant\default` bundles Ant; falls back to the system `ANT_HOME` when missing. Build entry: `ant.bat` (Linux/macOS: `ant.sh`). Only needed to rebuild the project JAR — not required to run.

### Dependencies

H2, Tomcat, GraalVM and the JsPlus runtime are bundled as plain JARs under `LibHome/`. No Maven/Gradle resolution and no server or IDE plugin is involved; upgrading a library means swapping the JAR and rebuilding.

## Quick start

First run only — create the schema once:

| Step | Command | Purpose |
| --- | --- | --- |
| 0 | `JsPlus\bin\StartH2Console.bat tomcatdb` | Open the H2 SQL console on `tomcatdb`; execute `dbscript\change.sql` there |

Routine startup:

| Step | Command | Purpose |
| --- | --- | --- |
| 1 | `JsPlus\bin\StopAll.bat` | Stop all services |
| 2 | `JsPlus\bin\BG_StartH2.bat` | Start H2 in the background, hidden window (TCP `3307`) |
| 3 | `JsPlus\bin\BG_StartTomcat.bat` | Start the embedded Tomcat in the background (HTTP `8080`, HTTPS `8443`) |

Then open the bundled sample webapp:

```
http://localhost:8080        HTTP
https://localhost:8443       HTTPS, dev self-signed certificate in JsPlus\key\
```

A script engine may be passed as the first argument, e.g. `BG_StartTomcat.bat graalvm.js` (default). Use the foreground `StartH2` / `StartTomcat` variants to keep a console window attached. `StopTomcat` / `StopH2` stop one service, `StopAll` stops everything.

## Configuration

### `JsPlus/config/Tomcat.properties`

| Key | Shipped value | Description |
| --- | --- | --- |
| `port` / `sslPort` | `8080` / `8443` | HTTP and HTTPS connector ports |
| `Webapp.contextPath[n]` / `Webapp.appBase[n]` | `/` → `webapps/default/` | webapp deployments (any number of pairs) |
| `maxThreads` / `maxPostSize` / `maxSavePostSize` | `1000` / `200MB` | worker threads and request size limits |
| `connectionTimeout` / `keepAliveTimeout` | `300000` | connector timeouts (ms) |
| `reloadable` | `true` | hot reload of deployed webapps |
| `SSL.enable` / `SSL.keyRoot` | `true` / `${JS_PLUS_PATH}/key/` | SSL switch and keystore root |
| `SSL.keystoreFile[0]` / `SSL.keystorePass[0]` / `SSL.keyAlias[0]` | `default_keystore.jks` / `changeit` / `client` | default certificate |
| `SSL.truststoreFile` / `SSL.truststorePass` / `SSL.truststoreType` | `default_truststore.jks` / `changeit` / `JKS` | client-authentication truststore |
| `Http2.enable` / `Http2.maxConcurrentStreams` | `true` / `200` | HTTP/2 switch and concurrent-stream cap |
| `TomcatScriptConsole.poolSize` / `cacheSeconds` / `freedDelayMs` | `100` / `2` / `10` | console pool size, param cache, release delay |
| `TomcatScriptConsole.engineName` | `graalvm.js` | JS engine (nashorn fallback available) |
| `DomainProxy.enable` | `true` | register the reverse-proxy servlet on `/*` |
| `DomainProxy.proxyConnectTimeout` / `proxyRequestTimeout` | `60s` / `30s` | upstream connect and whole-request timeouts |
| `SecurityFilter.cacheSeconds` | `5` | auth-check cache duration (s); `0` disables caching |
| `IpFilter.enable` / `IpFilter.cacheSeconds` | `true` / `30` | IP filter switch and rule cache duration |
| `IpAttackDefense.enable` / `logTimer` / `ruleCacheSeconds` | `true` / `1000` / `60` | defense switch, batch flush interval (ms), rule cache |
| `IpAttackDefense.logRetention` / `logPhysicalDelete` | `7d` / `30d` | soft-delete and physical-delete age for `ip_request_log` (`0` disables) |
| `errorRedirectEnable` / `errorRedirectUrl` | `false` | answer `302` instead of an error body |

`DomainProxy.enable=false` (also the code default when the key is absent) skips the proxy servlet: the deployed webapps answer requests directly and the server behaves as a plain embedded-Tomcat web server. The auth filter stays registered either way. Additional connector attributes may be supplied through `JsPlus/config/Attributes.properties`, applied to every connector on reload.

Additional connector controls, applied only when present (`-1` or blank keeps the Tomcat default): `maxConnections`, `asyncTimeout`, `connectionUploadTimeout`, `disableUploadTimeout`, `maxCookieCount`, `maxParameterCount`, `encoding`, `compressionEnable`, `compressableMimeType`, `compressionMinSize`.

### `JsPlus/config/Appenv.properties`

Environment values such as `TOMCAT_PORT` and `H2_PORT` (default `3307`, override with `JsPlus_H2_PORT`), plus `Custom_*` / `Env_*` keys consumed by applications.

### `JsPlus/config/DataSource.properties`

H2 connection pool for `jdbc:h2:tcp://localhost:${H2_PORT}/tomcatdb`, user `sample_user`, password created by `dbscript/change.sql`.

### Web logging (`WebLog.*`)

| Key | Default | Description |
| --- | --- | --- |
| `WebLog.Enable` | `true` | master switch |
| `WebLog.Folder` | `${JS_PLUS_PATH}/logs/domain` | CSV output folder |
| `WebLog.MaxFileSize` / `MaxBackupIndex` | `10MB` / `10` | rotation size and retained files |
| `WebLog.ArchiveDays` | `31` | delete files older than N days |
| `WebLog.ParamMaxLength` | `20` | truncate logged params and headers |
| `WebLog.Allowed` / `WebLog.Ignored` | — | regex lists selecting what is logged |

## Database (H2)

`dbscript/change.sql` creates the runtime tables; `dbscript/fallback.sql` drops them.

| Table | Purpose |
| --- | --- |
| `domain_auth` | auth switch and per-domain AES `encrypt_key`, plus lockout policy |
| `domain_auth_user` | usernames with AES-encrypted passwords and per-account lock state |
| `domain_mapping_param` | domain → proxy target parameter rows |
| `domain_ssl_host` | additional SSL host certificates (`keystore_pass` encrypted per row) |
| `domain_ip_rule` | IP rules, one ALLOW/DENY + pattern per row (manual and auto-ban rows) |
| `ip_request_log` | per-request records written in batches |
| `ip_defense_rule` | attack-defense thresholds, optionally narrowed to a URI regex |
| `ip_ban` | automatic bans with `expire_time` |
| `ghost_remote_command` | remote command queue used by the ghost consoles |

## Domain proxy and security

### Reverse proxy (`DomainProxy`)

Every request is resolved per `domain:port`, first against `domain_mapping_param`, then against a per-domain script `JsPlus/domain/<domain>_<port>.js`:

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

Two optional hooks extend the chain. `DomainMapping.filter()` rewrites the upstream response (gzip-aware) before it is returned. `DomainMapping.preRequest()` runs **before** the upstream call and acts as a cache/stub barrier: return `null` to let the request proceed normally, or return an object to answer without touching the backend:

```js
DomainMapping.preRequest = function () {
    if (request.getRequestURI() === '/health') {
        return { status: 200, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: 'ok' };
    }
    return null;   // continue proxying
};
```

The returned map takes `status` (number, default `200`), `headers` (name → value) and `body` (`string` or `byte[]`). A short-circuited request never reaches the upstream. Manage mappings from the console with `tomcat.setDomainMapping(domain, port, params)`, `removeDomainMapping` and `queryDomainMapping(s)`; changes reach the proxy cache immediately. A `domain:port` without a mapping answers `502 Domain config not found`.

### Basic Auth (`SecurityFilter`)

```js
tomcat.createDomainAuth('127.0.0.1', '8443', true);
tomcat.createDomainAuthUser('127.0.0.1', '8443', 'Sample', 'password');
```

Passwords are stored AES-encrypted against the per-domain `encrypt_key`. **Account lockout:** a wrong password increments `failed_attempts`; at `max_attempts` the account is locked until `locked_until` and then auto-unlocks on the next attempt — no timer job. While locked, even the correct password is rejected with `401`. A successful login resets the counter, and changing a password clears any lock. Policy defaults are `max_attempts=5`, `lock_minutes=30`, adjustable per domain with `setAuthLockPolicy`.

### IP access control (`IpFilter`)

One rule per row (`ruleType` + one `ipPattern`), evaluated in this order:

1. **Allow wins** — a matching ALLOW rule admits the request even if a DENY rule also matches.
2. **Deny** — a matching DENY rule rejects with `403`.
3. **Whitelist mode** — a domain with allow rules but no match rejects the IP.
4. **No rules** — the request continues to `SecurityFilter` and the webapp.

A row with `domain="0"`, `port="0"` is a server-wide default for every `domain:port` without rules of its own; precise rules always win over `0:0`.

```js
tomcat.createDomainIpRule("0", "0", "ALLOW", "192.168.1.0/24");
tomcat.createDomainIpRule("sample.com", "8080", "DENY", "203.0.113.7");
```

### IP attack defense (`IpAttackDefense`)

The filter runs before `IpFilter` in the chain. Each request is pushed onto a `CacheArray` queue and flushed to `ip_request_log` in batches, so the hot path never blocks on JDBC. Per enabled rule the engine keeps a sliding-window counter (key = rule + IP). On reaching the threshold it writes a DENY row into `domain_ip_rule` — blocked immediately by `IpFilter` — records the ban in `ip_ban` with `expire_time`, and lifts both when the ban expires. A defense or DB failure never blocks request serving.

```js
tomcat.createDefenseRule("burst-limit", "0", "0", "", 60, 120, 600, true);
tomcat.createDefenseRule("api-login-burst", "sample.com", "8080", "^/api/login", 60, 10, 3600, true);
tomcat.queryIpBans();
tomcat.removeIpBan("sample.com", "8080", "203.0.113.7");
```

### SSL / TLS

Development certificates ship in `JsPlus/key/`. Extra hosts are applied to the running connector by `addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` or `loadDomainSslHosts()`, with no restart; `createDomainSslHost` / `updateDomainSslHost` / `removeDomainSslHost` change the DB record only and take effect on the next boot. Optional mTLS uses `clientAuth` plus the truststore settings above.

## Management API

Every method lives on the `tomcat` object exposed by `JsPlus/script/TomcatEmbed.js`, prints `resMap.msg` to the console and returns it as a string.

### Lifecycle

| Method | Description |
| --- | --- |
| `tomcat.start()` / `stop()` / `restart()` | Load `Tomcat.properties` and boot, stop, or restart the embedded Tomcat |

### Basic Auth

| Method | Description |
| --- | --- |
| `createDomainAuth(domain, port, authEnable)` / `updateDomainAuth` / `removeDomainAuth` | Manage the auth switch for a `domain:port` |
| `queryDomainAuth(domain, port)` / `queryDomainAuths()` | Read one or all auth configs |
| `createDomainAuthUser(domain, port, username, plaintextPass)` / `updateDomainAuthUser` / `removeDomainAuthUser` | Manage users (AES-encrypted passwords) |
| `setAuthLockPolicy(domain, port, maxAttempts, lockMinutes)` / `unlockAuthUser(domain, port, username)` | Lockout policy and manual unlock |

### Domain mappings

| Method | Description |
| --- | --- |
| `setDomainMapping(domain, port, domainParams)` | Create or update a mapping, effective at once |
| `removeDomainMapping(domain, port)` | Remove a mapping |
| `queryDomainMapping(domain, port)` / `queryDomainMappings()` | Read one or all mappings |

### SSL hosts

| Method | Description |
| --- | --- |
| `createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` / `updateDomainSslHost` / `removeDomainSslHost` | Manage DB records (effective on restart) |
| `queryDomainSslHost(hostname)` / `queryDomainSslHosts()` | Read one or all SSL hosts |
| `addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Apply a host to the running server, persisting the record when new |
| `loadDomainSslHosts()` | Reload every host from the DB onto the running server |

### IP access rules

| Method | Description |
| --- | --- |
| `createDomainIpRule(domain, port, ruleType, ipPattern)` | Add one manual rule (`ALLOW` / `DENY`) |
| `updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)` | Change one manual rule's pattern; auto-ban rows are never touched |
| `removeDomainIpRule(domain, port, ruleType, ipPattern)` | Soft-delete one manual rule; lift an auto-ban with `removeIpBan` instead |
| `queryDomainIpRule(domain, port, ruleType, ipPattern)` / `queryDomainIpRules()` | Read one or all rules, each tagged `manual` or `ip_ban` |
| `loadDomainIpRules()` | Drop the rule cache so the next request re-reads the DB |

### IP attack defense

| Method | Description |
| --- | --- |
| `createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | Add a rule (`"0","0"` = server-wide; `uriPattern` optional) |
| `updateDefenseRule(ruleId, ...)` / `removeDefenseRule(ruleId)` | Update or soft-delete a rule |
| `queryDefenseRules()` / `queryIpBans()` | List rules or active bans |
| `removeIpBan(domain, port, ip)` | Lift a ban now, deleting its DENY rule |
| `queryIpRequestLogs()` / `clearIpRequestLogs()` | Inspect the latest records or purge the log |

## Quick test

| Step | Command | Purpose |
| --- | --- | --- |
| 1 | `test\TestAll.bat` (Linux/macOS: `test\TestAll.sh`) | Full suite: `StopAll` → clean `.tmp` and `logs` → init databases → start H2 → boot the embedded Tomcat → **139 checks** → load test against a standalone Tomcat → write reports and archive them |

For a single suite, run the matching `test\Test<Suite>.bat` / `.sh`; each one stops services, prepares H2, and writes its own report, so a module can be verified without the full run.

| Suite | Coverage |
| --- | --- |
| `TestDomainProxy.js` | 26 cases — mapping CRUD plus live reverse-proxy requests, dispatch and validation |
| `TestSecurityFilter.js` | 16 cases — auth and user CRUD plus live `401` / `200` checks |
| `TestIpFilter.js` | 20 cases — rule CRUD plus live `403` / `200` checks and allow-over-deny |
| `TestIpAttackDefense.js` | 16 cases — defense-rule CRUD and the live auto-ban flow |
| `TestTomcatServer.js` | 36 cases — connector contract, webapp config, keystore readiness, SSL host CRUD, HTTP/2 handshake |
| `TestAuthLockout.js` | 7 cases — lockout threshold, rejection while locked, unlock and counter reset |
| `TestFreed.js` | 6 cases — shared-infrastructure smoke checks |
| `TestServerLog.js` | 11 cases — async log pipeline, CSV shape, batching and per-domain isolation |

Every case prints `[PASS]` / `[FAIL]` and writes a colour-coded HTML and Markdown report to `docs/latest/report/` — one pair per suite plus `TestAll-Report` and `TestAll-CI-Summary.md`. Non-query cases append a `dbVerify` block showing the executed `SELECT` and its rows, and each suite ends with a cleanup case so re-runs never pollute one another.

Suites may also be driven from a running console:

```js
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestAll.js');
/
```

The load test (`test/LoadTest.js`) is a pure-JS run through the console. Defaults are `https://localhost:8443/default/`, 10 concurrent × 1000 requests, 30 s timeout — `TestAll` raises the total to 10000; override with `LOADTEST_URL`, `LOADTEST_CONCURRENCY`, `LOADTEST_TOTAL` and `LOADTEST_TIMEOUT`. It writes `docs/latest/report/loadtest-result.json` (QPS, avg, p50/p90/p95/p99, max, status distribution, transport errors) and an offline dashboard `loadtest-dashboard.html`. Both record the runtime environment of the run — OS, CPU cores, memory, JVM heap, JDK and JVM arguments — so a QPS figure is only compared against runs captured on the same machine.

## Build

```sh
# Linux / macOS
ant

# Windows
ant.bat
```

Compiles `src/` and packages `com/**` and `org/**` into `LibHome/jsplus/default/jsplus-tomcat-<version>.jar`. Every successful build increments the patch in `version.properties`, appends one dated line to `changes.log` and `changes_zh-HK.log`, and regenerates the API site into `docs/latest/api/` with an archived copy at `docs/apis/<version>.jar` (`-Djavadoc.skip=true` skips it). Pass `-Dchange.note="..."` to record a description on the history line.

`ant.sh` / `ant.bat` resolve `JAVA_HOME` and `ANT_HOME` from the bundled `Java/default/` and `Ant/default/` first, falling back to the system values or `PATH`.

## AI-Agent collaborative development

JsPlus-Tomcat is built to be developed with AI agents — automated build, AI-authored code and tests, fully automated verification, and human review of the reports form a closed loop.

| Stage | Description |
| --- | --- |
| **Automated environment setup** | `ant.bat` builds in one command (version management → packaging → javadoc); only GraalVM 25 is required (Ant optional); Windows and Linux share the same `.bat` / `.sh` scripts, so the environment is reproducible |
| **AI-assisted coding** | The embedded GraalVM JS console runs new logic immediately — no restart, no recompilation; the whole server is reachable as the `tomcat` object |
| **AI-authored test cases** | Standardized JS suites (`Case Cxx` naming over the `TestBase` harness) plug straight into `TestAll`; each module also has its own launcher for a fast single-suite cycle |
| **Fully automated verification** | Local one-click `TestAll.bat` / `TestAll.sh`; CI (GitHub Actions) builds, runs the suite and archives the reports |
| **Human review of reports** | `TestAll-Report` and `TestAll-CI-Summary` carry per-case results, `dbVerify` output and failure details; machines judge the tests, humans own the quality |

## License

Released under the **MIT License**. See [LICENSE](LICENSE) for the full text.
