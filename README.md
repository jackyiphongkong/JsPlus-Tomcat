# JsPlus-Tomcat

> **A lightweight, Java-native Nginx alternative.** One JVM, one folder: reverse proxy, web server and
> per-domain gateway built on embedded **Apache Tomcat 11** — scriptable from JS, zero plugins.
> **Download, unzip and run** — no installer, no system-level configuration, ready to serve.

An embedded **Apache Tomcat 11** server for the JsPlus runtime (`com.killcoding.jsplus.tomcat`).
Like Nginx it fronts many domains from a single process, but in pure Java: per-domain reverse
proxying, per-domain Basic Auth, hot-reloadable SSL certificates, request resource allocation and
GraalVM JS request consoles — with no XML server config, no external install and no plugins to
manage. It boots a full Tomcat from Java code and adds a scriptable layer on top, so the whole
server (start, stop, routing, auth, SSL) is managed in one line from the JS console.

> Version: `7.0.0 (2026.10.02)` · Base source: `apache-tomcat-11.0.1-embed.zip`

---

## Highlights — what makes it different

Most embedded-Tomcat projects only embed the engine. JsPlus-Tomcat turns the server into a
**scriptable, database-driven runtime**:

- **A Java-native Nginx alternative** — Nginx-style reverse proxying, per-domain virtual hosting, SSL
  termination, IP attack defense and connection/resource control all run in a single JVM with zero
  plugins; no separate reverse-proxy tier to deploy, configure or maintain.
- **The whole server is a JS object** — start, stop, restart, and every management task
  (`createDomainAuth`, `setDomainMapping`, `createDomainSslHost`, `addSslHost`,
  `loadDomainSslHosts`, `createDomainIpRule`, `createDefenseRule`, ...) are one-line calls from the
  GraalVM JS console. No XML, no restart.
- **Runtime configuration lives in H2, not in files** — `domain_auth`, `domain_mapping_param`,
  `domain_ssl_host`, `domain_ip_rule` and `ip_defense_rule` are the live source of truth: auth,
  IP access, mapping and attack-defense changes apply immediately (proxy cache / auth checks), SSL
  host changes follow the hot-reload rules below, and everything is re-applied automatically at startup.
- **Per-domain multi-tenancy on a single Tomcat** — each `domain:port` gets its own reverse-proxy
  target, its own Basic Auth, its own IP allow/deny rules, its own attack-defense rules and its own
  SSL certificate, all managed independently.
- **Hot-reloadable SSL** — a new certificate hot-loads onto the running server via `addSslHost(...)`;
  `updateDomainSslHost()` and `removeDomainSslHost()` only change the DB record and require a Tomcat
  restart to take effect.
- **Standalone single-JVM deployment** — one folder, one JVM: Tomcat + H2 + GraalVM JS runtime +
  webapps, with no external server or DBMS to install.
- **Fine-grained request resource allocation** — connector tuning (`maxThreads`, `maxConnections`,
  timeouts, size limits, compression) plus a pooled JS console that bounds scriptable processing.
- **Simple code, easy to maintain** — the server is a handful of plain Java classes in one package;
  every dependency is a plain JAR bundled under `LibHome/`, easy to find and ship — no Maven/Gradle
  dependency resolution, no IDE or server plugins to install. Upgrading the embedded Tomcat (or any
  library) means swapping the JARs and rebuilding with `ant`: no config migration, no plugin management.

| Classic Tomcat | JsPlus-Tomcat |
|---|---|
| `server.xml` + restart to change anything | JS console API, effective at runtime |
| SSL change ⇒ restart | `addSslHost()` hot reload for new hosts; update/remove ⇒ restart |
| Static webapp deployment | Programmatic deployment; per-domain proxy, auth and SSL |
| External DB / extra installs | Embedded H2 + GraalVM JS in a single JVM |

Compared to **Nginx**:

| Nginx | JsPlus-Tomcat |
|---|---|
| Written in C; native install + `nginx.conf` edits + `nginx -s reload` | Pure Java, embedded Tomcat; download, unzip and run — no install |
| Static config; changes need file edits and a reload | Scriptable from the JS console + DB-driven config, effective at runtime |
| Extensions via compiled C modules | Extend via JS scripts (GraalVM) and plain-JAR dependencies |
| SSL/cert management is manual per server block | Hot-reloadable SSL, DB-backed hosts, per-domain certificates |

---

## Features

- **Embedded Tomcat 11** — programmatic startup via `TomcatServer`, no XML server config required.
- **Multi-webapp deployment** — any number of `Webapp.contextPath[n]` / `Webapp.appBase[n]` pairs from `tomcat.properties`.
- **HTTP + HTTPS dual connector** — default `8080` (HTTP) and `8443` (HTTPS); per-host keystores and
  **hot reload of SSL certificates** without restarting the server.
- **Web server & request resource allocation** — serves HTTP/HTTPS with fine-grained controls over
  how network requests consume resources: worker threads (`maxThreads`), connection caps
  (`maxConnections`), timeouts, request-size limits, GZIP compression and a pooled JS console for
  scriptable per-request handling.
- **Connector tuning** — `maxThreads`, `maxPostSize`, `maxSavePostSize`, `maxCookieCount`,
  `maxParameterCount`, `asyncTimeout`, `connectionTimeout`, `keepAliveTimeout`, `maxConnections`,
  upload timeouts and GZIP compression, all configurable.
- **HTTP/2** — `h2` over TLS (ALPN negotiation on the SSL connector) and `h2c`
  via HTTP/1.1 Upgrade / prior knowledge on the plain connector
  (`Http2.enable`, `Http2.maxConcurrentStreams`).
- **External web resources** — mount extra JARs (`jarResourcePath`), an external classes directory
  (`classesPath`) and external static directories (`webResourcePath` + `webResourceMount`) into a webapp.
- **DomainProxy (reverse proxy)** — maps a `domain:port` to a backend target, scriptable in JS
  per domain (see `JsPlus/domain/*.js`), with hop-by-hop header stripping, forwarded/response headers
  and a JS `filter()` hook (gzip-aware body rewriting).
- **SecurityFilter (Basic Auth)** — per `domain:port` authentication backed by H2, AES-encrypted
  passwords, cached lookups, plus a full management API from the JS console.
- **IpFilter (IP allow/deny)** — per `domain:port` access control backed by H2: one rule per row
  (`ALLOW` / `DENY` + one IP pattern), supporting exact IPs, CIDR blocks and wildcards, with a
  server-wide `0:0` fallback rule; rejected requests get `403`, changes apply immediately.
- **IpAttackDefense (IP attack defense)** — every request is recorded to `ip_request_log` with
  batched, asynchronous DB inserts (CacheArray + CacheDriverExecutor); configurable rules
  count requests per IP in a sliding window (`domain:port`, optionally narrowed to a URI regex)
  and auto-ban offending IPs into `domain_ip_rule` (DENY) until the ban expires.
- **TomcatScriptConsole** — a pooled set of GraalVM JS script consoles so request handling can be
  extended with scripts; dynamic pool size with usage metrics.
- **ServerLog** — asynchronous CSV web-access logging with batching, size-based rotation,
  archiving and URI allow/ignore rules.
- **Graceful shutdown** — `stop()` with a 30-second force-stop fallback.
- **Standalone deployment** — the web server, H2 database, JS runtime and webapps all boot from
  one directory (`JsPlus/`) in a single JVM process; no external Tomcat or database server to install.

---

## Architecture

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

**Runtime flow**

```
JsPlus/bin/StartH2.sh (or BG_StartH2.sh for background execution)
        │
        ▼
JsPlus/bin/StartTomcat.sh (or BG_StartTomcat.sh)
        │
        ▼
com.killcoding.jsplus.Main (java -cp "<LibHome>/*" -ScriptEngine graalvm.js -Env script -WriteHistory false -GhostName H2Ghost -Task script/StartTomcat.js)
        │
        ▼
TomcatEmbed.js ──► reads config/tomcat.properties, builds the parameter map
        │
        ▼
TomcatServer (background thread)
        ├──► creates HTTP/HTTPS connectors
        ├──► deploys every configured webapp
        ├──► registers IpFilter (per-domain IP allow/deny)
        ├──► registers SecurityFilter (per-domain Basic Auth)
        └──► registers DomainProxy servlet when proxying is enabled
        │
        ▼
Loads domain mappings
        ├──► H2: domain_mapping_param
        └──► per-domain JS scripts under JsPlus/domain/
```

---

## Repository layout

```
JsPlus-Tomcat/
├── src/                        # Java sources of this project
│   └── com/killcoding/jsplus/tomcat/
│       ├── TomcatServer.java        # embedded Tomcat bootstrap & lifecycle
│       ├── SecurityFilter.java      # per-domain Basic Auth filter
│       ├── IpFilter.java            # per-domain IP allow/deny filter
│       ├── DomainProxy.java         # scriptable reverse proxy servlet
│       ├── ServerLog.java           # async CSV web logger
│       └── TomcatScriptConsole.java # GraalVM JS console pool
├── JsPlus/                     # JsPlus runtime home (JS_PLUS_PATH)
│   ├── bin/                    # Start/Stop scripts (.sh / .bat)
│   ├── config/                 # tomcat.properties, DataSource, Logger, env …
│   ├── script/                 # JS bootstrap: StartTomcat.js, TomcatEmbed.js …
│   ├── domain/                 # per-domain mapping scripts (domain:port.js)
│   ├── webapps/                # bundled sample webapps (app, trade)
│   ├── key/                    # dev keystore.jks / truststore.jks
│   ├── ghost/                  # ghost helper scripts (H2Ghost, TomcatGhost)
├── LibHome/                    # dependency JARs (tomcat, h2, graalvm, nashorn, jsplus …)
├── dbscript/                   # change.sql / fallback.sql (H2 schema)
├── build.xml                   # Ant build → LibHome/jsplus/default/jsplus-tomcat.jar
├── ant.bat                     # Windows Ant wrapper
└── ant.sh                      # Linux/macOS Ant wrapper
```

---

## Requirements

- **Java** — a JDK under `../Java/default/` (e.g. GraalVM JDK 25), or any JDK reachable via
  `JAVA_HOME` / `PATH` (launcher scripts fall back automatically).
- **Apache Ant** — only needed to rebuild the `jsplus-tomcat.jar` (not required to run); bundled
  in `ant/default/`, falling back to the system `ANT_HOME` / PATH.
- **H2** — bundled in `LibHome/h2/default/`.

---

## Build

Compiles `src/` and packages `com/**` and `org/**` into:

```
LibHome/jsplus/default/jsplus-tomcat.jar
```

```sh
# Linux / macOS
ant

# Windows
ant.bat
```

`ant.sh` / `ant.bat` resolve `JAVA_HOME` and `ANT_HOME` from the bundled `Java/default/` and
`ant/default/` next to the script, falling back to the system `JAVA_HOME` / `ANT_HOME` (or PATH)
when the bundled copies are missing.

---

## Quick start

> Scripts use the bundled JDK at `JsPlus/../Java/default/bin/java` when present; otherwise they
> fall back to `JAVA_HOME` or `java` on PATH.

> **First-time setup — create the Tomcat database:** before the first run, apply `dbscript/change.sql`
> to the main database (`tomcatdb`) once, to create the user and tables the server needs:
> 1. `JsPlus/bin/StartH2Console.sh tomcatdb` (or `.bat`) — opens the H2 SQL console connected to `tomcatdb`.
> 2. Copy the contents of `dbscript/change.sql` and execute them in the console (or run
>    `RUNSCRIPT FROM '<full path to change.sql>';`).

```sh
# 1. Start the H2 database server (default TCP port 3306, see Appenv.properties)
JsPlus/bin/StartH2.sh          # or StartH2.bat on Windows

# 2. Start the embedded Tomcat (default HTTP 8080 / HTTPS 8443)
JsPlus/bin/StartTomcat.sh      # or StartTomcat.bat

# 3. Open the bundled sample webapp
#    http://localhost:8080        (HTTP)
#    https://localhost:8443       (HTTPS, dev self-signed certs in key/)
```

Notes:

- A script engine argument may be passed, e.g. `StartTomcat.sh graalvm.js` (default).
- Stop everything with `JsPlus/bin/StopAll.sh` (`.bat` on Windows); stop Tomcat or H2 individually
  with `StopTomcat` / `StopH2` (no argument needed).
- To run in the background (detached, no console window attached), use `JsPlus/bin/BG_StartH2` /
  `BG_StartTomcat` (`.sh` / `.bat`) instead of the foreground `Start*` scripts.
- The server also starts a **Ghost** console (`H2Ghost`) used for remote command tables.

---

## Standalone deployment

The system is designed to run as a **standalone, self-contained unit** — copy the folder to a
machine, start two scripts, and the whole stack (web server + database + scripting runtime +
webapps) is up. **Download, unzip and run** — the distribution is a plain folder; extract it
anywhere, point it at a JDK, and it is ready to use immediately.
Everything lives in one directory and runs in a single JVM process, so a single
node is sufficient to serve the full system.

- **One runtime home** — `JsPlus/` (`JS_PLUS_PATH`) holds everything: `config/`, `webapps/`, `key/`,
  `script/` and `ghost/`; all dependency JARs are bundled under `LibHome/`.
- **Bundled database** — H2 ships in `LibHome/h2/default/`; `bin/StartH2` starts the local H2 TCP
  server and the data files live under `Database/` (gitignored).
- **Single JVM** — `bin/StartTomcat` launches one Java process (`com.killcoding.jsplus.Main`) which
  boots the embedded Tomcat together with the H2 data sources and the GraalVM script consoles.
- **No external installs** — no standalone Tomcat and no separate DBMS are required; the only
  external component is a JDK under `../Java/`.
- **Same experience on Windows & Linux** — every script has a `.bat` and `.sh` twin
  (`StartH2`, `StartTomcat`, `StopH2`, `StopTomcat`, `StopAll`, ghost helpers).

Typical deployment steps:

1. Copy `JsPlus/` + `LibHome/` (and a JDK into `Java/default/`) to the target machine.
2. Run `JsPlus/bin/StartH2` — starts the H2 database server.
3. Run `JsPlus/bin/StartTomcat` — starts the embedded Tomcat on `8080` / `8443`.
4. Stop everything with `JsPlus/bin/StopAll`.

---

## Request forwarding (Web Server/Proxy)

One switch in `JsPlus/config/tomcat.properties` decides the server's personality:

| `DomainProxy.enable` | Mode | Behaviour |
|---|---|---|
| `false` (code default when the key is absent) | **Standalone mode** | No proxy servlet; the server serves the webapps deployed under `JsPlus/webapps/` directly on `8080`/`8443` — a plain embedded-Tomcat web server for a single site. |
| `true` (set by the shipped `tomcat.properties`) | **Web Server (Proxy) mode** | The `DomainProxy` servlet is registered on `/*`; per-`domain:port` mappings are loaded from H2 at startup and every request is routed to its backend target, with per-domain Basic Auth and hot-reloadable SSL on top. Use this to front multiple domains / backend apps from one JVM, like Nginx. |

How it works under the hood:

- `DomainProxy.enable=false` (also the code fallback when the key is absent): `addServlet` is skipped, so
  no proxy routing — requests are served by the deployed webapps themselves. The auth filter is
  still registered, so per-`domain:port` Basic Auth keeps working.
- `DomainProxy.enable=true`: the `DomainProxy` servlet is mapped to `/*` and `loadDomainMapping()` replays
  every row of `domain_mapping_param` into the proxy cache at startup; mappings added later via
  `tomcat.setDomainMapping(...)` take effect immediately. A request for a `domain:port` without a
  mapping answers `502 Domain config not found`.

To switch modes, edit `tomcat.properties`, then restart — the change applies from the next boot.

---

## Configuration

### `JsPlus/config/tomcat.properties`

| Key | Shipped value | Description |
|---|---|---|
| `port` | `${TOMCAT_PORT}` (8080) | HTTP connector port |
| `sslPort` | `8443` | HTTPS connector port |
| `Webapp.contextPath[n]` / `Webapp.appBase[n]` | `/` → `webapps/default/` | webapp deployments |
| `maxThreads` / `maxPostSize` / `maxSavePostSize` | `1000` / `200MB` | connector limits |
| `connectionTimeout` / `keepAliveTimeout` | `300000` | connector timeouts (ms) |
| `reloadable` | `true` | hot reload of deployed webapps |
| `SSL.enable` / `SSL.keyRoot` | `true` / `key/` | SSL switch and keystore root |
| `SSL.keystoreFile[0]` / `SSL.keystorePass[0]` / `SSL.keyAlias[0]` | `keystore.jks` / `tomcat` / `tomcat` | default certificate |
| `SSL.truststoreFile` / `SSL.truststorePass` / `SSL.truststoreType` | `truststore.jks` / `tomcat` / `JKS` | client-auth truststore |
| `Http2.enable` | `true` | HTTP/2 switch — `h2` via ALPN on the SSL connector, `h2c` upgrade on the plain connector |
| `Http2.maxConcurrentStreams` | `200` | max concurrent HTTP/2 streams per connection |
| `TomcatScriptConsole.poolSize` / `cacheSeconds` / `freedDelayMs` | `100` / `2` / `10` | script console pool tuning |
| `TomcatScriptConsole.engineName` | `graalvm.js` | JS engine (nashorn fallback available) |
| `DomainProxy.enable` | `true` | enable the reverse-proxy servlet |
| `DomainProxy.proxyConnectTimeout` | `60s` | upstream connect timeout |
| `DomainProxy.proxyRequestTimeout` | `30s` | whole-request timeout (connect + response headers); per-domain `requestTimeout` overrides |
| `SecurityFilter.cacheSeconds` | `5` | auth-check cache duration (s) |
| `IpFilter.enable` | `true` | master switch for the IP allow/deny filter |
| `IpFilter.cacheSeconds` | `30` | per-domain IP-rule cache duration (s) |
| `IpAttackDefense.enable` | `true` | master switch for IP request logging + attack defense |
| `IpAttackDefense.logTimer` | `1000` | batch flush interval for `ip_request_log` (ms) |
| `IpAttackDefense.ruleCacheSeconds` | `60` | defense-rule cache duration (s) |
| `IpAttackDefense.logRetention` | `7d` | soft-delete `ip_request_log` rows older than this (0 = disabled; `ms\|s\|m\|h\|d\|w`) |
| `IpAttackDefense.logPhysicalDelete` | `30d` | physically delete rows soft-deleted longer than this (0 = disabled) |
| `errorRedirectEnable` / `errorRedirectUrl` | `false` | send 302 instead of error body |
| `WebLog.*` | — | see *Web logging* below |

Additional connector attributes may be supplied via `JsPlus/config/attributes.properties`
(hot-reloaded, applied to all connectors).

### Request resource allocation (connector tuning)

Beyond the parameters in the table above, `TomcatServer` exposes these network/resource controls.
Each is applied only when set in `tomcat.properties`; blank or `-1` means Tomcat's own default is kept:

| Key | Shipped value | What it controls |
|---|---|---|
| `maxConnections` | — | max simultaneous connections accepted by the connector |
| `asyncTimeout` | — | timeout for async requests (ms) |
| `connectionUploadTimeout` | — | upload timeout; uploads bypass `connectionTimeout` |
| `disableUploadTimeout` | `true` | disable the connection timeout while uploading |
| `maxCookieCount` | — | max number of cookies per request |
| `maxParameterCount` | — | max request parameters (form/query) |
| `encoding` | `UTF-8` | URI body encoding |
| `compressionEnable` | `false` | enable GZIP response compression |
| `compressableMimeType` | — | MIME types eligible for compression |
| `compressionMinSize` | `1024` | minimum response size to compress (bytes) |

For scriptable request handling, the **GraalVM JS console pool** itself is a resource-allocation
knob: `TomcatScriptConsole.poolSize` bounds how many requests can be processed through scripts at
once, `cacheSeconds` controls how long console/domain params are cached, and `freedDelayMs` sets
how quickly a released console returns to the pool. A warning is logged when pool usage passes 80%.

### `JsPlus/config/Appenv.properties`

Environment/overridable values such as `TOMCAT_PORT`, `H2_PORT` (default `3306`, override with
`JsPlus_H2_PORT`) and various `Custom_*` / `Env_*` keys used by applications.

### `JsPlus/config/DataSource.properties`

H2 connection pool for the main database (`jdbc:h2:tcp://localhost:${H2_PORT}/tomcatdb`,
user `sample_user`, default password set in `dbscript/change.sql`).

### SSL / TLS

- Development certificates ship in `JsPlus/key/` (`keystore.jks`, `truststore.jks`).
- Extra per-host certificates can be added at runtime:
  `addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` and reloaded via
  `reloadSslHostConfig(hostName)` — no server restart required.
- Optional mTLS via `clientAuth`, `truststoreFile`, `truststorePass`, `truststoreType`.
- **DB-backed SSL hosts** — manage hosts via the `domain_ssl_host` table from the JS console
  (`tomcat.createDomainSslHost(...)`, `updateDomainSslHost`, `removeDomainSslHost`,
  `queryDomainSslHost(s)`, `tomcat.loadDomainSslHosts()`). Persisted rows auto-apply at startup.
  Each row's `keystore_pass` is stored AES-encrypted with its own per-row `encrypt_key`
  (following the DomainAuth pattern); legacy plaintext rows keep working and are
  re-encrypted on their next update. Only `tomcat.addSslHost(...)` applies to the running
  connector immediately (and persists the row); `updateDomainSslHost` / `removeDomainSslHost`
  change the DB only — restart Tomcat for them to take effect.

---

## Database (H2)

Apply `dbscript/change.sql` on the main DB to create:

| Table | Purpose |
|---|---|
| `domain_auth` | per `domain:port` auth switch + AES `encrypt_key` |
| `domain_auth_user` | usernames with AES-encrypted passwords |
| `domain_mapping_param` | domain → proxy target parameter rows |
| `domain_ssl_host` | additional SSL host certificate rows (`keystore_pass` AES-encrypted per row) |
| `domain_ip_rule` | per `domain:port` IP rules (one rule per row: ALLOW/DENY + pattern) |
| `ip_request_log` | per-IP request records (batched inserts, see *IP attack defense*) |
| `ip_defense_rule` | attack-defense rules (threshold; optional URI regex; `"0","0"` = server-wide) |
| `ip_ban` | automatic bans written by the defense engine (with expiry) |

`ghost_remote_command` holds remote command queue rows used by the ghost consoles.
`dbscript/fallback.sql` drops all tables listed above.

---

## Domain Proxy & Security

### Reverse proxy (`DomainProxy`)

With `DomainProxy.enable=true`, every request hits the proxy servlet. The target is resolved
per `domain:port` from:

1. H2 `domain_mapping_param` rows (loaded at startup), or
2. a per-domain JS script in `JsPlus/domain/<domain>_<port>.js` exposing `DomainMapping.getParams()`:

```js
DomainMapping.getParams = function(){
    return {
        target: 'https://killcoding.com',   // backend target
        connectTimeout: '60s',
        forwardedHeaders: { header_A: 'value_1' },
        responseHeaders:  { header_1: 'value_1' },
        forwardedHeader: true,
        filterEnable: true
    };
};
```

A `DomainMapping.filter()` hook may rewrite the upstream response (gzip-aware) before it is returned.

Mappings can also be managed from the JS console — `tomcat.setDomainMapping(domain, port, params)`,
`tomcat.removeDomainMapping(domain, port)` and `tomcat.queryDomainMapping(s)` — persisted to
`domain_mapping_param` and applied to the proxy cache immediately.

**Example — route a `domain:port` in one call:**

```js
// Forward every request for sample.com:8080 to the backend app on 127.0.0.1:8081
tomcat.setDomainMapping("sample.com", "8080", {
    target:       "http://127.0.0.1:8081",      // backend base URL (required)
    scriptEnable: true,                         // run the per-domain JS script
    scriptFile:   "sample.com.js" // per-domain params come from this file
});
// console: DomainMapping sample.com:8080 created.

// Verify it took effect
tomcat.queryDomainMapping("sample.com", "8080");
// console: DomainMapping sample.com:8080 ... (id, domain, port, paramValue, timestamps)
```

The `target` must be a valid backend base URL; without it the proxy answers `502 Target domain not found`.
Omitting `scriptFile` falls back to the per-domain script matched by `domain:port` (see `JsPlus/domain/`).

### Basic Auth (`SecurityFilter`)

Enable auth for a domain and create users from the JS console (exposed on `TomcatEmbed`):

```js
tomcat.createDomainAuth('127.0.0.1', '8443', true);
tomcat.createDomainAuthUser('127.0.0.1', '8443', 'Sample', 'password');
```

Management APIs: `updateDomainAuth`, `removeDomainAuth`, `queryDomainAuth(s)`,
`createDomainAuthUser`, `updateDomainAuthUser`, `removeDomainAuthUser`, `queryDomainAuthUser(s)`.
Passwords are stored AES-encrypted and verified against the per-domain `encrypt_key`.

### IP access control (`IpFilter`)

Per-`domain:port` allow/deny rules backed by the `domain_ip_rule` table, managed from the JS
console. **Each row is one rule**: a `ruleType` (`ALLOW` / `DENY`) and a single `ipPattern`;
pattern formats are **exact IP** (`192.168.1.10`), **CIDR** (`192.168.1.0/24`) and **wildcard**
(`192.168.1.*`). Set `IpFilter.enable=false` in `tomcat.properties` to switch the whole filter off
(all requests pass through); the filter defaults to enabled.

Evaluation order per request:

1. **Allow wins** — any matching `ALLOW` rule (per-domain or the `0:0` fallback) lets the request
   through, even if a deny rule also matches.
2. **Deny** — a request matching a `DENY` rule is rejected with `403`.
3. **Whitelist mode** — a domain with allow rules rejects IPs that match none of them.
4. **No rules** — everything else passes through to `SecurityFilter` / the webapp.

**Server-wide fallback rule (`0:0`)** — following `SecurityFilter`, a row with `domain = "0"`
and `port = "0"` acts as a global default: it applies to every `domain:port` that has no rules of
its own. Precise `domain:port` rules always win over the `0:0` fallback.

```js
// Server-wide default: block everyone, then allow the office ranges everywhere
tomcat.createDomainIpRule("0", "0", "DENY", "0.0.0.0/0");
tomcat.createDomainIpRule("0", "0", "ALLOW", "192.168.1.0/24");
```

```js
// Allow the office ranges, then block one offender — each rule is one row
tomcat.createDomainIpRule("sample.com", "8080", "ALLOW", "192.168.1.0/24");
tomcat.createDomainIpRule("sample.com", "8080", "ALLOW", "10.10.0.*");
tomcat.createDomainIpRule("sample.com", "8080", "DENY", "203.0.113.7");
// console: DomainIpRule sample.com:8080 [ALLOW] 192.168.1.0/24 created.

// Whitelist mode — allow only 127.0.0.1 on the admin port
tomcat.createDomainIpRule("sample.com", "8443", "ALLOW", "127.0.0.1");

// Correct a pattern, then verify and list
tomcat.updateDomainIpRule("sample.com", "8080", "ALLOW", "10.10.0.*", "10.20.0.*");
tomcat.queryDomainIpRule("sample.com", "8080", "DENY", "203.0.113.7");
tomcat.queryDomainIpRules();
```

### IP attack defense (`IpAttackDefense`)

Records every request into `ip_request_log` and optionally auto-bans IPs that exceed a configured
threshold. Logging is **asynchronous and batched** — each request is pushed onto a `CacheArray`
queue and flushed to the DB in batches by a background `CacheArrayFilter` thread using
`CacheDriverExecutor.executeBatch` (same pattern as `ServerLog`), so the hot path never blocks on
a JDBC write.

**How a rule matches** — every rule is matched by `domain:port`; `"0"` / `"0"` makes it
server-wide (the same `0:0` convention as `IpFilter` / `SecurityFilter`). When `uriPattern`
is set, the request URI must also match the regex (`Pattern.matcher().find()`); when it is
blank, every URI on the matched `domain:port` counts.

**How a ban works** — per enabled rule, the engine keeps a sliding-window counter per IP in the
shared `Cache` (window = `window_seconds`). When an IP's count reaches `threshold`, the engine:
writes a `DENY` row into `domain_ip_rule` (so `IpFilter` blocks the IP immediately), records the
ban in `ip_ban` with `expire_time`, and auto-removes the rule + soft-deletes the ban when the ban
expires. Defense runs before `IpFilter` in the filter chain; a defense/DB failure never blocks
request serving.

**Workflow**

```
Request ──► IpAttackDefense.doFilter
   │
   ├─ 1. record()  ──► push one row onto CacheArray ──► background thread flushes to
   │                   ip_request_log in batches (every logTimer ms)
   │
   └─ 2. checkDefense() ──► load enabled rules (cached for ruleCacheSeconds)
          │
          └─ sliding-window counter per IP (key = ruleId + IP, window = window_seconds)
                 │
                 ├─ count < threshold ──────────► pass
                 └─ count ≥ threshold ──► ban()
                        ├─ write DENY into domain_ip_rule (tagged with ip_ban_id) ──► IpFilter blocks immediately
                        ├─ write ip_ban with expire_time = now + ban_seconds
                        └─ subsequent requests ──► IpFilter ──► 403 Access denied

Background scan (every ≥ 5s): expireBans()
   └─ ip_ban.expire_time < now ──► soft-delete the ban ──► delete the matching DENY rule
          ──► IP unblocked automatically
```

```js
// Server-wide: any IP doing > 120 requests/min across the whole server is banned for 600s
tomcat.createDefenseRule("burst-limit", "0", "0", "", 60, 120, 600, true);

// Per domain:port: > 30 requests/min to sample.com:8080 → ban 300s
tomcat.createDefenseRule("login-burst", "sample.com", "8080", "", 60, 30, 300, true);

// Per domain:port + URI: > 10 requests/min hitting /api/login → ban 3600s
tomcat.createDefenseRule("api-login-burst", "sample.com", "8080", "^/api/login", 60, 10, 3600, true);

// Manage rules and bans
tomcat.queryDefenseRules();
tomcat.updateDefenseRule("defenseRuleId", "burst-limit", "0", "0", "", 60, 120, 600, true);
tomcat.removeDefenseRule("defenseRuleId");

tomcat.queryIpBans();
tomcat.removeIpBan("0", "0", "203.0.113.7");      // manually un-ban now

// Inspect / purge the request log (also queryable via SQL)
tomcat.queryIpRequestLogs();
tomcat.clearIpRequestLogs();
```

Changes take effect immediately (the per-domain cache is invalidated on every write; the next
request re-reads the DB). `tomcat.loadDomainIpRules()` drops the whole cache to force a re-read
without waiting for expiry.

---

## TomcatEmbed.js API reference

Every management call is a method on the `tomcat` object exposed by `JsPlus/script/TomcatEmbed.js`.
Each method prints the result (`resMap.msg`) to the console and returns it as a string.

### Lifecycle

| Method | Description |
|---|---|
| `tomcat.start()` | Load `JsPlus/config/tomcat.properties` and boot the embedded Tomcat |
| `tomcat.stop()` | Stop the embedded Tomcat |
| `tomcat.restart()` | `stop()` then `start()` |

### Per-domain Basic Auth (`SecurityFilter`)

| Method | Description |
|---|---|
| `tomcat.createDomainAuth(domain, port, authEnable)` | Enable/disable Basic Auth for a `domain:port` |
| `tomcat.updateDomainAuth(domain, port, authEnable)` | Update the auth switch for a `domain:port` |
| `tomcat.removeDomainAuth(domain, port)` | Remove the auth config for a `domain:port` |
| `tomcat.queryDomainAuth(domain, port)` | Show one auth config |
| `tomcat.queryDomainAuths()` | Show all auth configs |
| `tomcat.createDomainAuthUser(domain, port, username, plaintextPass)` | Create a user (password AES-encrypted) |
| `tomcat.updateDomainAuthUser(domain, port, username, plaintextPass)` | Update a user's password |
| `tomcat.removeDomainAuthUser(domain, port, username)` | Remove a user |

### Domain mappings (`DomainProxy`)

| Method | Description |
|---|---|
| `tomcat.setDomainMapping(domain, port, domainParams)` | Create or update a `domain:port` mapping (see the example above) |
| `tomcat.removeDomainMapping(domain, port)` | Remove a mapping |
| `tomcat.queryDomainMapping(domain, port)` | Show one mapping |
| `tomcat.queryDomainMappings()` | Show all mappings |

### SSL hosts (`domain_ssl_host`)

| Method | Description |
|---|---|
| `tomcat.createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Persist a new SSL host record |
| `tomcat.updateDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Update the DB record (restart Tomcat to take effect) |
| `tomcat.removeDomainSslHost(hostname)` | Soft-delete the record |
| `tomcat.queryDomainSslHost(hostname)` | Show one SSL host |
| `tomcat.queryDomainSslHosts()` | Show all SSL hosts |
| `tomcat.addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Apply a host to the running server (persists the record when new) |
| `tomcat.loadDomainSslHosts()` | Reload all hosts from DB and apply to the running server |

### IP access rules (`domain_ip_rule`)

| Method | Description |
|---|---|
| `tomcat.createDomainIpRule(domain, port, ruleType, ipPattern)` | Add one manual rule (`ruleType` = `ALLOW` / `DENY`); rows written by `ip_ban` are never matched by the duplicate check |
| `tomcat.updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)` | Change the pattern of one manual rule only (auto-ban rows are never touched) |
| `tomcat.removeDomainIpRule(domain, port, ruleType, ipPattern)` | Soft-delete one manual rule only — to lift an auto-ban, use `tomcat.removeIpBan(...)` |
| `tomcat.queryDomainIpRule(domain, port, ruleType, ipPattern)` | Show one rule, including its `Source` (`manual` / `ip_ban`) and `IpBanId` |
| `tomcat.queryDomainIpRules()` | Show all rules; each row is tagged `(manual)` or `(ip_ban)` |
| `tomcat.loadDomainIpRules()` | Clear the rule cache so the next request re-reads the DB |

### IP attack defense (`ip_defense_rule` / `ip_ban` / `ip_request_log`)

| Method | Description |
|---|---|
| `tomcat.createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | Add a defense rule (`"0","0"` = server-wide; `uriPattern` optional URI regex) |
| `tomcat.updateDefenseRule(ruleId, ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | Update a rule |
| `tomcat.removeDefenseRule(ruleId)` | Soft-delete a rule |
| `tomcat.queryDefenseRules()` | Show all rules |
| `tomcat.queryIpBans()` | Show all automatic bans |
| `tomcat.removeIpBan(domain, port, ip)` | Remove a ban now (also deletes the DENY rule) |
| `tomcat.queryIpRequestLogs()` | Show the latest 100 request records |
| `tomcat.clearIpRequestLogs()` | Delete all request records |

---

## Web logging (`ServerLog`)

`WebLog.*` keys in `tomcat.properties`:

| Key | Default | Description |
|---|---|---|
| `WebLog.Enable` | `true` | master switch |
| `WebLog.Folder` | `${JS_PLUS_PATH}/logs/domain` | CSV output folder |
| `WebLog.MaxFileSize` | `10MB` | rotate size |
| `WebLog.MaxBackupIndex` | `10` | kept rotated files |
| `WebLog.ArchiveDays` | `31` | delete logs older than N days |
| `WebLog.ParamMaxLength` | `20` | truncate params/headers in log |
| `WebLog.Allowed` / `WebLog.Ignored` | — | regex lists to filter what is logged |
| `WebLog.Type` | — | comma-separated types to log |

Logs are written asynchronously in batches (default 1s timer) to daily CSV files.

---

## Command-line arguments

The shell/bat launchers pass command-line options to the JsPlus main class
(`com.killcoding.jsplus.Main`), e.g.:

```sh
-ScriptEngine graalvm.js    # JS engine (nashorn fallback available)
-Env script                 # runtime environment
-WriteHistory false         # disable console history
-GhostName H2Ghost          # ghost console name
-Task script/StartTomcat.js # bootstrap script
```

Options use the `-key value` (or `--flag` boolean) form. Inside this project, server startup
parameters come from `JsPlus/config/tomcat.properties` via `TomcatServer.loadTomcatProps(...)`
instead of command-line parsing.

---

## Automated Testing

### Automated test scripts (console mode)

Five runnable suites live in `test/` (repo-root).
They run inside the live JsPlus console — no third-party test framework and no
launcher scripts needed:

| Script | Coverage |
|---|---|
| `TestBase.js` | Shared harness (Java bindings, `check`/`isOk`/`msg`, `callTomcat`, `runCmd`/`curlStatus`, `dbVerify` SQL verification, HTML/MD/CI-summary report writers) |
| `TestAll.js` | Unified entry — runs all five suites in sequence and prints an aggregate pass/fail summary |
| `TestDomainProxy.js` | 21 cases — domain mapping CRUD + live reverse-proxy request via `curl` (incl. `mode=internal` dispatch + validation)
| `TestSecurityFilter.js` | 16 cases — basic-auth CRUD (`domain_auth` / `domain_auth_user`) + live `401`/`200` checks via `curl` |
| `TestIpFilter.js` | 20 cases — IP rule CRUD (`domain_ip_rule`, ALLOW/DENY incl. `uri` prefix + ALLOW-over-DENY) + live `403`/`200` checks via `curl` |
| `TestIpAttackDefense.js` | 16 cases — defense-rule CRUD (`ip_defense_rule` / `ip_ban` / `ip_request_log`) + live auto-ban flow (`403`) |
| `TestTomcatServer.js` | 34 cases — TomcatServer behaviour through the `tomcat` console entry (`TomcatEmbed.js`): `addSslHost` + cleanup, `Webapp.contextPath`/`Webapp.appBase` config contract (incl. legacy-key regression guard), `SSL.*` block, keystore readiness (JKS + alias + trust anchor), read-only behaviour smoke (`getTomcat` / `queryDomainSslHosts` / `loadDomainSslHosts`), DomainSslHost CRUD, and the HTTP/2 contract (`Http2.enable=true` config + live h2c handshake on the plain connector) — every API call records its `{code, msg}` return value, DB writes carry a `dbVerify` (`SELECT *`) verification + cleanup |

Run the full suite in one go (stops services -> DB init -> H2 -> embedded
Tomcat -> 108 checks -> load test against a standalone Tomcat ->
`test_reports/`):

- Windows: `test\TestAll.bat`
- Linux: `test/TestAll.sh`

Or, with H2 and Tomcat already started (e.g. `StartH2` then `StartTomcat`),
run everything directly from the JS console:

```js
Console.eval(System.getenv('JS_PLUS_PATH') + '/test/TestAll.js');
/
```

Or run an individual suite:

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

Prerequisites (only for JS-console runs; `TestAll.bat` / `TestAll.sh` handle all of this automatically):

- Start the services first: `StartH2.bat/sh`, then `StartTomcat.bat/sh`.
- For the SecurityFilter suite, set `SecurityFilter.cacheSeconds=0` in
  `JsPlus/config/tomcat.properties` and restart Tomcat — otherwise the default
  60 s auth-enable cache makes the live checks read stale state.
- For the IpFilter suite, set `IpFilter.cacheSeconds=0` in
  `JsPlus/config/tomcat.properties` and restart Tomcat — the live `403`/`200`
  checks must read the rule list unconditionally.
- For the IpAttackDefense suite, set `IpFilter.cacheSeconds=0` and
  `IpAttackDefense.ruleCacheSeconds=0` in `JsPlus/config/tomcat.properties` and
  restart Tomcat — the live auto-ban flow (defense rule + auto-written
  `ip_ban` DENY rule) must take effect immediately.

Each check prints `[PASS]` / `[FAIL]`, and a color-coded HTML report
(green PASS / red FAIL, with Case Id / Test Objective / Result / Detail) is
written to `test_reports/TestDomainProxy-Report.html`,
`test_reports/TestSecurityFilter-Report.html`,
`test_reports/TestIpFilter-Report.html`,
`test_reports/TestIpAttackDefense-Report.html` and
`test_reports/TestTomcatServer-Report.html`.

Step 3 of `TestAll.bat` / `TestAll.sh` then starts a standalone Tomcat and runs
the built-in load test (`test/LoadTest.js`, a pure-JS port executed through the
JsPlus console — no separate Java compilation step; defaults:
`https://localhost:8443/default/`, 10 concurrent x 1000 requests, 30 s timeout;
override via `LOADTEST_URL` / `LOADTEST_CONCURRENCY` / `LOADTEST_TOTAL` /
`LOADTEST_TIMEOUT`), writing `test_reports/loadtest-result.json` (raw + summary
metrics: QPS, avg, p50/p90/p95/p99, max, status distribution, transport errors)
and an offline HTML chart dashboard `test_reports/loadtest-dashboard.html`
(no CDN, no JS — metric cards, status distribution, per-second throughput SVG
and a latency histogram). The load test warms up with a few unmeasured requests
and retries transient transport-level failures (connect/reset/timeout) twice,
so only requests that still fail after retry are counted as `failed`. It uses
`HttpClient.sendAsync` in batches with `CompletableFuture.allOf().get()` for
concurrency (pure Java blocking calls — safe on the GraalVM JS runtime, which
rejects multi-threaded access to JS callbacks) and points the default trust
store at `JsPlus/key/default_truststore.jks` for the self-signed 8443
certificate.

After the load test, `TestAll.bat` / `TestAll.sh` stop the standalone Tomcat
and then run `StopAll` to shut down every remaining service (including H2), so
the whole run leaves nothing behind. (TestAll.js exits via `System.exit(0)`
directly instead of `ScriptConsole.exit()`, which would write the shared
`JsPlus/.tmp/exit.tmp` signal file and take the H2 ghost — and any other JsPlus
JVM watching that file — down with it.)

Every non-query case appends a `dbVerify` block: the executed `SELECT` statement
and its result rows are rendered as an HTML table (with a `row num` column) in
small yellow text, so testers can verify the persisted table state directly
(executed through the project's `CacheDriverExecutor.find`, the same data-access
path the APIs use). Each suite finishes with a cleanup-all case that removes its
remaining test data (e.g. the precondition domain mapping) and verifies the
affected tables are empty (`deleted=FALSE` -> 0 rows), so re-runs and the
combined `TestAll.js` run never pollute each other.

### Continuous Integration (GitHub Actions)

`.github/workflows/ci.yml` runs the full suite on every push / pull request
against `main` / `master`:

1. Check out the repository.
2. Set up an Oracle GraalVM 25 (`$JAVA_HOME` is used as the fallback Java).
3. Install Ant and build `LibHome/jsplus/default/jsplus-tomcat.jar` (`ant build`).
4. Run `test/TestAll.sh` — the same one-command flow as locally.
5. Upload `test_reports/` and `logs/` as the `test-reports` artifact (downloadable from the
   Actions run page).

Note: `Java/` (bundled JDKs) is git-ignored, so the CI runner relies on the
system JDK; `LibHome/` (all runtime JARs) is committed.

---

## License

Released under the **MIT License**. See [LICENSE](LICENSE) for the full text.
