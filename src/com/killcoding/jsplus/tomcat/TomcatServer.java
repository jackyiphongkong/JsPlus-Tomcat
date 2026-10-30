/*******************************************************************************
The MIT License (MIT)

Copyright (c) 2024 KILLCODING.COM

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*****************************************************************************/
package com.killcoding.jsplus.tomcat;

import java.io.File;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.catalina.webresources.DirResourceSet;
import com.killcoding.log.LoggerFactory;
import com.killcoding.tool.ConfigProperties;
import com.killcoding.tool.CipherTools;
import com.killcoding.tool.Base64Tools;
import com.killcoding.file.BaseFile;

import app.ext.GlobalScriptConsole;

import com.killcoding.tool.CommonTools;
import com.killcoding.datasource.CacheDriverExecutor;
import com.killcoding.datasource.Clock;
import com.killcoding.ext.Json;
import com.killcoding.log.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Embedded Apache Tomcat 11 server lifecycle manager.
 * <p>
 * Boots connectors (HTTP/1.1 + HTTP/2, optional SSL per hostname), registers
 * the gateway filters ({@link DomainProxy}, {@link SecurityFilter},
 * {@link IpFilter}, {@link IpAttackDefense}) and webapps from the
 * {@code Webapp.*} config, and exposes the management APIs
 * (SSL hosts, domain mappings, auth, IP rules) used by the JS console.
 * <p>
 * When {@code DomainProxy.enable=true}, the first webapp's context path is
 * forced to {@code /} (or a temporary empty ROOT webapp is created) so the
 * proxy filter chain always has a root to dispatch into.
 */
public class TomcatServer {

	public final static String VERSION = "${BuildVersion.VERSION}(${BuildVersion.BUILD_DATE})";

	public final static String TOMCAT_HOME_KEY = "TOMCAT_HOME";

	public static String TOMCAT_HOME;

	/**
	 * The currently running server instance, used to live-apply domain_ssl_host
	 * CRUD.
	 */
	private static volatile TomcatServer runningServer = null;

	protected Logger log = null;

	protected List<Map<String, String>> webapps = null;
	protected Integer port = 8080;
	protected Integer sslPort = 8433;
	protected String hostname = "localhost";
	protected Boolean enableSSL = false;
	protected Boolean http2Enable = false;
	protected Integer http2MaxConcurrentStreams = 200;
	protected String keyRoot = null;

	protected String clientAuth = "false";
	protected String sslProtocol = "TLS";

	protected List keystoreFile = null;
	protected List keystorePass = null;
	protected List keyAlias = null;

	protected String truststoreFile = null;
	protected String truststorePass = null;
	protected String truststoreType = null;

	protected String encoding = "UTF-8";

	protected Integer maxThreads = -1;
	protected Integer maxPostSize = -1;
	protected Integer maxSavePostSize = -1;
	protected Integer maxCookieCount = -1;
	protected Integer maxParameterCount = -1;
	protected Long asyncTimeout = -1L;
	protected Long connectionTimeout = -1L;
	protected Long keepAliveTimeout = -1L;
	protected Integer maxConnections = -1;
	protected Long connectionUploadTimeout = -1L;
	protected Boolean disableUploadTimeout = true;
	protected Boolean reloadable = true;

	protected String jarResourcePath = null;
	protected String webResourcePath = null;
	protected String webResourceMount = null;
	protected String classesPath = null;
	protected String tmpDir = null;

	protected Boolean compressionEnable = false;
	protected String compressableMimeType = null;
	protected Integer compressionMinSize = 1024;

	protected Tomcat tomcat = null;

	protected Connector sslConnector = null;

	protected Connector connector = null;

	protected Boolean proxyEnable = false;

	/** Creates a server instance (no-op wiring; config comes via {@link #loadParams}). */
	public TomcatServer() throws Exception {
		super();
		log = LoggerFactory.getLogger(TomcatServer.class);
	}

	/** Resets all runtime state so the server can be re-initialized. */
	private void reset() {
		webapps = new ArrayList<Map<String, String>>();
		tmpDir = TOMCAT_HOME;
		tomcat = null;
	}

	/**
	 * Parses the full {@code TomcatServer} configuration block (connectors,
	 * SSL, webapps, filters, DomainProxy, console pool, ...) into the shared
	 * {@code config} map.
	 */
	public void loadParams(Map params) {
		ConfigProperties paramsCp = new ConfigProperties();
		paramsCp.putAllAndReturnSelf(params);

		this.proxyEnable = paramsCp.getBoolean("DomainProxy.enable", false);
		this.encoding = paramsCp.getString("encoding", null);
		this.maxThreads = paramsCp.getInteger("maxThreads", -1);
		this.maxPostSize = paramsCp.getInteger("maxPostSize", -1);
		this.maxSavePostSize = paramsCp.getInteger("maxSavePostSize", -1);
		this.maxCookieCount = paramsCp.getInteger("maxCookieCount", -1);
		this.maxParameterCount = paramsCp.getInteger("maxParameterCount", -1);
		this.asyncTimeout = paramsCp.getLong("asyncTimeout", -1L);
		this.connectionTimeout = paramsCp.getLong("connectionTimeout", -1L);
		this.keepAliveTimeout = paramsCp.getLong("keepAliveTimeout", -1L);
		this.maxConnections = paramsCp.getInteger("maxConnections", -1);
		this.disableUploadTimeout = paramsCp.getBoolean("disableUploadTimeout", true);
		this.reloadable = paramsCp.getBoolean("reloadable", true);
		this.connectionUploadTimeout = paramsCp.getLong("connectionUploadTimeout", -1L);
		this.port = paramsCp.getInteger("port", 8080);
		this.sslPort = paramsCp.getInteger("sslPort", this.port);
		this.hostname = paramsCp.getString("hostname", "localhost");
		this.enableSSL = paramsCp.getBoolean("SSL.enable", false);
		// B15 fix: surface the ambiguous config where HTTP and HTTPS share one
		// port instead of silently serving both protocols on the same port.
		if (this.enableSSL && this.sslPort == this.port) {
			Logger.systemWarn(TomcatServer.class,
					"SSL.enable=true but 'sslPort' is not set (defaults to port={}); the same port will serve both HTTP and HTTPS.", this.port);
		}
		this.http2Enable = paramsCp.getBoolean("Http2.enable", false);
		this.http2MaxConcurrentStreams = paramsCp.getInteger("Http2.maxConcurrentStreams", 200);
		this.keyRoot = paramsCp.getString("keyRoot", TOMCAT_HOME + "/key/");
		this.clientAuth = paramsCp.getString("clientAuth", "false");
		this.sslProtocol = paramsCp.getString("sslProtocol", "TLS");
		this.keyAlias = paramsCp.getArray("SSL.keyAlias", new String[] {});
		this.keystorePass = paramsCp.getArray("SSL.keystorePass", new String[] {});
		this.keystoreFile = paramsCp.getArray("SSL.keystoreFile", new String[] {});
		this.truststoreFile = paramsCp.getString("SSL.truststoreFile", null);
		this.truststorePass = paramsCp.getString("SSL.truststorePass", null);
		this.truststoreType = paramsCp.getString("SSL.truststoreType", null);
		this.jarResourcePath = paramsCp.getString("jarResourcePath", null);
		this.webResourcePath = paramsCp.getString("webResourcePath", null);
		this.webResourceMount = paramsCp.getString("webResourceMount", null);
		this.classesPath = paramsCp.getString("classesPath", null);
		this.tmpDir = paramsCp.getString("tmpDir", TOMCAT_HOME);
		this.compressionEnable = paramsCp.getBoolean("compressionEnable", false);
		this.compressableMimeType = paramsCp.getString("compressableMimeType", null);
		this.compressionMinSize = paramsCp.getInteger("compressionMinSize", 1024);
		// B12 fix: keep a static snapshot for the static SSL host APIs.
		staticConfig = paramsCp;

		Iterator<Object> itr = paramsCp.keySet().iterator();
		while (itr.hasNext()) {
			String key = (String) itr.next();
			Pattern r = Pattern.compile("Webapp\\.contextPath([\\[]{0,1}[0-9]*[\\]]{0,1})");
			Matcher m = r.matcher(key);
			if (m.find()) {
				// 'index' is format '[number]'
				String index = m.group(1);
				String contextPath = (String) paramsCp.getString(String.format("Webapp.contextPath%s", index));
				String appBase = (String) paramsCp.getString(String.format("Webapp.appBase%s", index));
				if (!CommonTools.isBlank(appBase)) {
					Map<String, String> webapp = new HashMap<String, String>();
					webapp.put("contextPath", contextPath);
					webapp.put("appBase", appBase);
					webapps.add(webapp);
				}
			}
		}
	}

	public Map<String, Object> addSslHost(String hostname, String _keystoreFile, String _keystorePass,
			String _keyAlias) {
		Map<String, Object> resMap = new HashMap<>();
		try {
			addSslHost(true, hostname, _keystoreFile, _keystorePass, _keyAlias);
			resMap.put("code", 1);
			resMap.put("msg", String.format("SSLHost %s added.", hostname));
		} catch (Exception e) {
			log.warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", String.format("Failed to add SSLHost %s.", hostname));
		}
		return resMap;
	}

	private void addSslHost(boolean isNew, String hostname, String _keystoreFile, String _keystorePass,
			String _keyAlias) throws Exception {
		if (CommonTools.isBlank(hostname)) {
			throw new IllegalArgumentException("The param 'hostname' is required.");
		}
		if (CommonTools.isBlank(_keystoreFile)) {
			throw new IllegalArgumentException("The param 'keystoreFile' is required.");
		}
		if (CommonTools.isBlank(_keystorePass)) {
			throw new IllegalArgumentException("The param 'keystorePass' is required.");
		}
		if (CommonTools.isBlank(_keyAlias)) {
			throw new IllegalArgumentException("The param 'keyAlias' is required.");
		}
		hostname = hostname.toLowerCase();
		File keystoreFileAbs = new File(String.format("%s/%s", keyRoot, _keystoreFile));
		if (keystoreFileAbs.exists()) {
			SSLHostConfig newHostConfig = new SSLHostConfig();
			newHostConfig.setHostName(hostname);
			SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(newHostConfig,
					SSLHostConfigCertificate.Type.RSA);
			certificate.setCertificateKeystoreFile(keystoreFileAbs.getAbsolutePath());
			certificate.setCertificateKeystorePassword(_keystorePass);
			certificate.setCertificateKeyPassword(_keystorePass);
			certificate.setCertificateKeyAlias(_keyAlias);
			newHostConfig.addCertificate(certificate);
			if (sslConnector != null) {
				sslConnector.addSslHostConfig(newHostConfig);
			}
			if (connector != null && sslConnector == null && enableSSL) {
				connector.addSslHostConfig(newHostConfig);
			}
			Logger.systemMark(TomcatServer.class, "SSLHost {} added.", hostname);
			if (isNew) {
				createDomainSslHost(hostname, _keystoreFile, _keystorePass, _keyAlias);
			}
		} else {
			Logger.systemWarn(TomcatServer.class, "Keystore not found, skipped: {}", _keystoreFile);
		}
	}

	/** Applies the configured keystore/certificate settings onto an SSL host config. */
	private void loadSSLHost(SSLHostConfig sslHostConfig) {
		int keystoreFileSize = keystoreFile.size();
		// B7 fix: guard against config where the three array lengths differ —
		// the old loop used keystoreFile.size() as the bound and threw
		// IndexOutOfBoundsException (failing the whole SSL connector init).
		int certCount = Math.min(keystoreFileSize, Math.min(keystorePass.size(), keyAlias.size()));
		if (certCount < keystoreFileSize) {
			Logger.systemWarn(TomcatServer.class,
					"SSL.keystoreFile has {} entry/entries but SSL.keystorePass/SSL.keyAlias only {}; configuring {} certificate(s).",
					keystoreFileSize, certCount, certCount);
		}
		for (int i = 0; i < certCount; i++) {
			String _keystoreFile = (String) keystoreFile.get(i);
			String _keystorePass = (String) keystorePass.get(i);
			String _keyAlias = (String) keyAlias.get(i);

			File keystoreFileAbs = new File(_keystoreFile);
			if (!keystoreFileAbs.exists()) {
				keystoreFileAbs = new File(String.format("%s/key/%s", TOMCAT_HOME, _keystoreFile));
			}
			if (!keystoreFileAbs.exists()) {
				log.warn("Keystore not found, skipped: {}", _keystoreFile);
				continue;
			}

			SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
					sslHostConfig, SSLHostConfigCertificate.Type.RSA);
			certificate.setCertificateKeystoreFile(keystoreFileAbs.getAbsolutePath());
			certificate.setCertificateKeystorePassword(_keystorePass);
			certificate.setCertificateKeyPassword(_keystorePass);
			certificate.setCertificateKeyAlias(_keyAlias);
			sslHostConfig.addCertificate(certificate);
			log.info("Certificate added to _default_: keystore={}, alias={}",
					keystoreFileAbs.getAbsolutePath(), _keyAlias);
		}
	}

	/** Enables SSL (TLS/HTTPS) on a connector with the configured keystore. */
	private void useSSL(Connector _connector) {
		_connector.setSecure(true);
		_connector.setScheme("https");
		setAttribute(_connector, "SSLEnabled", true);
		setAttribute(_connector, "isSecure", true);
		setAttribute(_connector, "clientAuth", clientAuth);
		setAttribute(_connector, "sslProtocol", sslProtocol);

		SSLHostConfig sslHostConfig = findSslHostConfigByHostName(_connector, "_default_");
		boolean isNew = (sslHostConfig == null);

		if (isNew) {
			sslHostConfig = new SSLHostConfig();
			sslHostConfig.setHostName("_default_");
			sslHostConfig.setSslProtocol(sslProtocol);
		}

		loadSSLHost(sslHostConfig);

		if (!CommonTools.isBlank(truststoreFile)) {
			File truststoreFileAbs = new File(truststoreFile);
			if (!truststoreFileAbs.exists()) {
				truststoreFileAbs = new File(String.format("%s/%s", this.keyRoot, truststoreFile));
			}
			if (truststoreFileAbs.exists()) {
				sslHostConfig.setTruststoreFile(truststoreFileAbs.getAbsolutePath());
				sslHostConfig.setTruststorePassword(truststorePass);
				sslHostConfig.setTruststoreType(truststoreType);
				log.info("Truststore configured on _default_: {}", truststoreFileAbs.getAbsolutePath());
			}
		}

		if (isNew) {
			_connector.addSslHostConfig(sslHostConfig);
			log.info("SSLHostConfig added: _default_");
		} else {
			reloadSslHostConfig(_connector, "_default_");
			log.info("SSLHostConfig reloaded: _default_");
		}
	}

	/** Finds the SSL host config bound to a hostname on a connector. */
	private SSLHostConfig findSslHostConfigByHostName(Connector _connector, String hostName) {
		SSLHostConfig[] configs = _connector.findSslHostConfigs();
		if (configs == null) {
			return null;
		}
		for (SSLHostConfig cfg : configs) {
			if (hostName.equals(cfg.getHostName())) {
				return cfg;
			}
		}
		return null;
	}

	/** Reloads SSL configuration for one hostname (management API, live apply). */
	public void reloadSslHostConfig(String hostName) {
		if (sslConnector != null) {
			reloadSslHostConfig(sslConnector, hostName);
		}
		if (connector != null && sslConnector == null && enableSSL) {
			reloadSslHostConfig(connector, hostName);
		}
	}

	/** Reloads SSL configuration on every connector (all hostnames). */
	public void reloadSslHostConfig() {
		reloadSslHostConfig("_default_");
	}

	/** Reloads the SSL host config for a hostname on one connector. */
	private void reloadSslHostConfig(Connector _connector, String hostName) {
		if (findSslHostConfigByHostName(_connector, hostName) == null) {
			Logger.systemMark(TomcatServer.class, "SSLHostConfig [{}] not found on connector port [{}], skip reload.",
					hostName, _connector.getPort());
			return;
		}

		try {
			Object protocolHandler = _connector.getProtocolHandler();
			if (protocolHandler instanceof Http11NioProtocol) {
				((Http11NioProtocol) protocolHandler).reloadSslHostConfig(hostName);
				Logger.systemMark(TomcatServer.class, "SSL certificate reloaded for hostName: {} on port {}",
						hostName, _connector.getPort());
			} else {
				Logger.systemMark(TomcatServer.class, "reloadSslHostConfig not supported for protocol handler: {}",
						protocolHandler.getClass().getName());
			}
		} catch (Exception e) {
			Logger.systemError(TomcatServer.class, "Failed to reload SSL config for hostName: " + hostName, e);
			log.error(e.getMessage(), e);
		}
	}

	/** Applies JVM/Tomcat system attributes from the config to a connector. */
	private void setAttributes(Connector _Connector) {
		try {
			File attsFile = new File(String.format("%s/config/attributes.properties", TOMCAT_HOME));
			if (attsFile.exists()) {
				ConfigProperties prop = new ConfigProperties();
				// Applies every attribute from attributes.properties onto the connector.
				final Runnable updatedRunnable = new Runnable() {
					@Override
					public void run() {
						List<String> propKeys = new ArrayList(prop.keySet());
						for (String pk : propKeys) {
							String v = prop.getString(pk, "");
							setAttribute(_Connector, pk, v);
						}
						Logger.systemMark(TomcatServer.class, "Attributes Reloaded");
					}
				};
				prop.load(attsFile, updatedRunnable);
				updatedRunnable.run();
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	/** Reads the {@code tomcat.properties} file into a flat params map (static). */
	public final static void loadTomcatProps(final Map<String, String> params) {
		try {
			if (CommonTools.isBlank(TOMCAT_HOME))
				TOMCAT_HOME = System.getenv(TOMCAT_HOME_KEY) == null ? System.getProperty(TOMCAT_HOME_KEY)
						: System.getenv(TOMCAT_HOME_KEY);

			if (CommonTools.isBlank(TOMCAT_HOME)) {
				String jsPlusPath = System.getProperty("JS_PLUS_PATH");
				TomcatServer.TOMCAT_HOME = jsPlusPath;
			}

			System.setProperty("TOMCAT_HOME", TOMCAT_HOME);
			System.setProperty("TOMCAT_PID", ProcessHandle.current().pid() + "");
			System.out.println("TOMCAT_HOME: " + System.getProperty("TOMCAT_HOME"));
			System.out.println("TOMCAT_PID: " + System.getProperty("TOMCAT_PID"));
			Logger.systemInfo(TomcatServer.class, "SystemProperties: {}", System.getProperties());
			File tomcatConfigFile = new File(String.format("%s/config/tomcat.properties", TOMCAT_HOME));
			if (tomcatConfigFile.exists()) {
				ConfigProperties cp = new ConfigProperties();
				// Copies every tomcat.properties entry into the startup params map.
				final Runnable updateTomcatPropRun = new Runnable() {
					@Override
					public void run() {
						List<String> tomcatCpKeys = new ArrayList(cp.keySet());
						for (String tccpKey : tomcatCpKeys) {
							String tccpVal = cp.getString(tccpKey, "");
							params.put(tccpKey, tccpVal);
							Logger.systemMark(TomcatServer.class, "Param: {}={}", tccpKey, tccpVal);
						}
						TomcatScriptConsole.setPoolSize(cp.getInteger("TomcatScriptConsole.poolSize", 1000));
						TomcatScriptConsole.setCacheSeconds(cp.getInteger("TomcatScriptConsole.cacheSeconds", 60));
						TomcatScriptConsole.setFreedDelayMs(cp.getLong("TomcatScriptConsole.freedDelayMs", 1000L));
						TomcatScriptConsole.setEngineName(cp.getString("TomcatScriptConsole.engineName", "graalvm.js"));
						DomainProxy.setParams(params);
						SecurityFilter.setParams(params);
						IpFilter.setParams(params);
						IpAttackDefense.setParams(params);
						Map webLogParam = cp.getMap("WebLog");
						if (webLogParam != null) {
							ServerLog.setParams(webLogParam);
						}
					}
				};
				cp.load(tomcatConfigFile, updateTomcatPropRun);
				updateTomcatPropRun.run();
			}
		} catch (Exception e) {
			Logger.systemError(TomcatServer.class, e.getMessage(), e);
		}
	}

	/** Returns the resolved TOMCAT_HOME folder path. */
	public String getTomcatHome() {
		return TOMCAT_HOME;
	}

	/** Returns the live embedded Tomcat instance (null before start). */
	public Tomcat getTomcat() {
		return tomcat;
	}

	/** B12 fix: static config snapshot so the static SSL host APIs can read
	 *  SSL.keystoreEncryptKey (the master AES key that must never be stored
	 *  in the DB next to the encrypted keystore password). */
	private static volatile ConfigProperties staticConfig;

	/** B3 fix: shared daemon lifecycle executor reused by start()/stop() — the
	 *  old code leaked a new single-thread pool on every call. */
	private volatile ExecutorService lifecycleExecutor;

	/** Resolves the keystore-password encryption key from config/environment. */
	private static String sslEncryptKeyConfig() {
		return staticConfig != null ? staticConfig.getString("SSL.keystoreEncryptKey", null) : null;
	}

	/** Daemon executor for async lifecycle work (start/stop/reload). */
	private ExecutorService lifecycleExecutor() {
		ExecutorService e = lifecycleExecutor;
		if (e == null || e.isShutdown()) {
			synchronized (this) {
				e = lifecycleExecutor;
				if (e == null || e.isShutdown()) {
					ThreadFactory tf = r -> {
						Thread t = new Thread(r, "jsplus-tomcat-lifecycle");
						t.setDaemon(true);
						return t;
					};
					e = Executors.newSingleThreadExecutor(tf);
					lifecycleExecutor = e;
				}
			}
		}
		return e;
	}

	/** Starts the server asynchronously (entry point for the JS console). */
	public void start(Map<String, String> params) {
		ExecutorService exec = lifecycleExecutor();
		// Runs the full synchronous start on the shared lifecycle executor.
		exec.execute(new Runnable() {
			@Override
			public void run() {
				startRun(params);
			}
		});
	}

	/** Sets the shared base attributes on every connector. */
	private void setBaseAttributes() {
		if (!CommonTools.isBlank(encoding)) {
			applyToConnectors(c -> {
				c.setUseBodyEncodingForURI(true);
				c.setURIEncoding(encoding);
			});
		}

		if (compressionEnable) {
			applyToConnectors(c -> {
				c.setProperty("compression", "on");
				if (!CommonTools.isBlank(compressionMinSize))
					c.setProperty("compressionMinSize", compressionMinSize.toString());

				if (!CommonTools.isBlank(compressableMimeType))
					c.setProperty("compressableMimeType", compressableMimeType);
			});
		}

		if (maxPostSize > -1) {
			applyToConnectors(c -> c.setMaxPostSize(maxPostSize));
		}

		if (maxSavePostSize > -1) {
			applyToConnectors(c -> c.setMaxSavePostSize(maxSavePostSize));
		}

		if (maxCookieCount > -1) {
			applyToConnectors(c -> c.setMaxCookieCount(maxCookieCount));
		}

		if (maxParameterCount > -1) {
			applyToConnectors(c -> c.setMaxParameterCount(maxParameterCount));
		}

		if (asyncTimeout > -1L) {
			applyToConnectors(c -> c.setAsyncTimeout(asyncTimeout));
		}

		if (maxThreads > -1) {
			applyToConnectors(c -> c.setProperty("maxThreads", maxThreads + ""));
		}

		if (connectionTimeout > -1L) {
			applyToConnectors(c -> setAttribute(c, "connectionTimeout", connectionTimeout));
		}

		if (keepAliveTimeout > -1L) {
			applyToConnectors(c -> setAttribute(c, "keepAliveTimeout", keepAliveTimeout));
		}

		if (maxConnections > -1) {
			applyToConnectors(c -> setAttribute(c, "maxConnections", maxConnections));
		}

		if (connectionUploadTimeout > -1L) {
			applyToConnectors(c -> setAttribute(c, "connectionUploadTimeout", connectionUploadTimeout));
		}

		applyToConnectors(c -> setAttribute(c, "disableUploadTimeout", disableUploadTimeout));
	}

	/** Runs an action against every active connector. */
	private void applyToConnectors(Consumer<Connector> action) {
		action.accept(connector);
		if (sslConnector != null) {
			action.accept(sslConnector);
		}
	}

	/** Synchronous start: builds the Tomcat instance, connectors, webapps and console. */
	private void startRun(Map<String, String> params) {
		try {
			reset();
			runningServer = this;
			loadParams(params);
			initTomcat();
			setBaseAttributes();
			addServiceConnectors();
			addWebapps();
			initConsole();
			if (proxyEnable) {
				loadDomainMapping();
			}
			if (enableSSL) {
				loadDomainSslHosts();
			}
			tomcat.start();
			tomcat.getServer().await();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	/** Builds the embedded Tomcat instance with its server and service. */
	private void initTomcat() {
		tomcat = new Tomcat();
		tomcat.setPort(port);
		tomcat.setHostname(hostname);
		String _tmpDir = new File(String.format("%s/.tmp/%s/", tmpDir, port)).getAbsolutePath();
		tomcat.setBaseDir(_tmpDir);

		connector = new Connector();
		connector.setPort(port);

		if (!port.equals(sslPort)) {
			sslConnector = new Connector();
			sslConnector.setPort(sslPort);
		}

		setAttributes(connector);

		if (sslConnector != null) {
			setAttributes(sslConnector);
		}

		if (enableSSL && sslConnector == null) {
			useSSL(connector);
		}

		if (enableSSL && sslConnector != null) {
			useSSL(sslConnector);
		}

		if (http2Enable) {
			if (connector != null) {
				useHttp2(connector);
			}
			if (sslConnector != null) {
				useHttp2(sslConnector);
			}
		}
	}

	/** Enables the HTTP/2 upgrade protocol on a connector. */
	private void useHttp2(Connector c) {
		Http2Protocol http2 = new Http2Protocol();
		if (http2MaxConcurrentStreams != null) {
			http2.setMaxConcurrentStreams(http2MaxConcurrentStreams);
		}
		c.addUpgradeProtocol(http2);
		log.info("HTTP/2 enabled on connector port {} (h2 over TLS / h2c upgrade).", c.getPort());
	}

	/** Adds the configured connectors (HTTP + HTTPS) to the service. */
	private void addServiceConnectors() {
		tomcat.getService().addConnector(connector);

		if (sslConnector != null) {
			tomcat.getService().addConnector(sslConnector);
		}

		tomcat.setConnector(connector);

		StandardServer server = (StandardServer) tomcat.getServer();
		AprLifecycleListener listener = new AprLifecycleListener();
		server.addLifecycleListener(listener);
	}

	/**
	 * Adds webapps from the {@code Webapp.*} config. When
	 * {@code DomainProxy.enable=true}, the first context is forced to
	 * {@code /} (a temporary empty ROOT webapp is created when none is
	 * configured) so the proxy filter chain has a root to dispatch into.
	 */
	private void addWebapps() {
		// DomainProxy.enable=true: any webapp configured with contextPath "/"
		// (e.g. Webapp.contextPath[0]=/) is ignored — the server creates its own
		// temporary ROOT webapp instead, so the filter chain applies to every
		// host:port request. All other webapps (e.g. /default_a, /default_b)
		// are deployed as usual.
		if (proxyEnable) {
			boolean ignoredRoot = false;
			Iterator<Map<String, String>> it = webapps.iterator();
			while (it.hasNext()) {
				Map<String, String> w = it.next();
				if ("/".equals(w.get("contextPath"))) {
					log.warn(String.format(
							"DomainProxy.enable=true: ignoring Webapp.contextPath[0]=/ (appBase=%s); a temporary ROOT webapp is created by the server instead.",
							w.get("appBase")));
					it.remove();
					ignoredRoot = true;
				}
			}
			if (!ignoredRoot) {
				log.info("DomainProxy.enable=true: no Webapp.contextPath[0]=/ configured; creating a temporary ROOT webapp.");
			}
			String tempAppBase = createTemporaryRootAppBase();
			addWebappContext("/", tempAppBase);
		}

		for (Map<String, String> webapp : webapps) {
			log.mark("Starting: {} -> {}", webapp.get("contextPath"), webapp.get("appBase"));
			addWebappContext(webapp.get("contextPath"), webapp.get("appBase"));
			log.info(String.format("Start Webapp: http://localhost:%d/%s --> %s", port, webapp.get("contextPath"),
					webapp.get("appBase")));
		}
	}

	/** Registers one webapp context at a context path backed by an appBase folder. */
	private void addWebappContext(String contextPath, String appBase) {
		Context context = tomcat.addWebapp(contextPath, appBase);
		context.setReloadable(reloadable);
		// Allow cross-context dispatch so DomainProxy "internal" mode can
		// forward (e.g. /test -> /default_a) from the ROOT context to another
		// webapp context; Tomcat defaults crossContext to false.
		context.setCrossContext(true);

		WebResourceRoot resources = new StandardRoot(context);
		addJarResources(resources);
		addClassesResources(resources);
		addWebResourceMount(resources);
		context.setResources(resources);

		addFilter(context);
	}

	// Fresh empty directory under java.io.tmpdir (never touches the user's
	// webapps folder) used only when DomainProxy is enabled with no webapp
	// configured; reuse a fixed name so repeated restarts don't accumulate.
	/** Creates a temporary empty ROOT webapp base (used when no Webapp is configured). */
	private String createTemporaryRootAppBase() {
		String tmp = System.getProperty("java.io.tmpdir");
		String base = tmp + File.separator + "jsplus-tomcat-tmp-root";
		File dir = new File(base);
		if (!dir.exists()) {
			dir.mkdirs();
		}
		return dir.getAbsolutePath();
	}

	/** Mounts the JsPlus jar resources (WEB-INF/lib) into a webapp's resource root. */
	private void addJarResources(WebResourceRoot resources) {
		if (jarResourcePath == null) {
			return;
		}
		String[] jarResourcePaths = jarResourcePath.split(",|;");
		for (String jarResPath : jarResourcePaths) {
			File jrp = new File(jarResPath);
			if (!jrp.exists()) {
				continue;
			}
			if (jrp.isFile()) {
				if (jrp.getName().toLowerCase().endsWith(".jar")) {
					resources.addPreResources(
							new JarResourceSet(resources, "/WEB-INF/classes", jrp.getAbsolutePath(), "/"));
				}
			} else if (jrp.isDirectory()) {
				File[] files = jrp.listFiles();
				for (File file : files) {
					if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
						resources.addPreResources(
								new JarResourceSet(resources, "/WEB-INF/classes", file.getAbsolutePath(), "/"));
					}
				}
			}
		}
	}

	/** Mounts the compiled classes folder into a webapp's resource root. */
	private void addClassesResources(WebResourceRoot resources) {
		if (classesPath == null) {
			return;
		}
		File classesPathFile = new File(classesPath);
		if (classesPathFile.exists()) {
			resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", classesPath, "/"));
		}
	}

	/** Mounts a configured extra resource directory into a webapp's resource root. */
	private void addWebResourceMount(WebResourceRoot resources) {
		if (webResourcePath == null || webResourceMount == null) {
			return;
		}
		File webResourcePathFile = new File(webResourcePath);
		if (webResourcePathFile.exists() && webResourceMount.startsWith("/")) {
			resources.addPreResources(new DirResourceSet(resources, webResourceMount, webResourcePath, "/"));
		}
	}

	/** Initializes the script console pool (once) before the server starts. */
	private synchronized void initConsole() {
		if (!GlobalScriptConsole.isLoadedScriptEngine()) {
			try {
				TomcatScriptConsole.initScriptConsole();
			} catch (Exception e) {
				Logger.systemError(DomainProxy.class, e.getMessage(), e);
			}
		}
	}

	/** Sets a string property on a connector via its attribute API. */
	private void setAttribute(Connector ctor, String name, Object value) {
		ctor.setProperty(name, value + "");
	}

	/** Registers the gateway filters on every webapp context. */
	private void addFilter(Context context) {
		IpAttackDefense ipAttackDefense = new IpAttackDefense();
		FilterDef ipAttackDefenseDef = new FilterDef();
		ipAttackDefenseDef.setFilter(ipAttackDefense);
		ipAttackDefenseDef.setFilterName(IpAttackDefense.FILTER_NAME);
		context.addFilterDef(ipAttackDefenseDef);

		final FilterMap ipAttackDefenseMap = new FilterMap();
		ipAttackDefenseMap.setFilterName(IpAttackDefense.FILTER_NAME);
		ipAttackDefenseMap.addURLPattern("/*");
		ipAttackDefenseMap.addServletName("*");
		ipAttackDefenseMap.setDispatcher("REQUEST");
		ipAttackDefenseMap.setDispatcher("FORWARD");
		context.addFilterMap(ipAttackDefenseMap);

		IpFilter ipFilter = new IpFilter();
		FilterDef ipFilterDef = new FilterDef();
		ipFilterDef.setFilter(ipFilter);
		ipFilterDef.setFilterName(IpFilter.FILTER_NAME);
		context.addFilterDef(ipFilterDef);

		final FilterMap ipFilterMap = new FilterMap();
		ipFilterMap.setFilterName(IpFilter.FILTER_NAME);
		ipFilterMap.addURLPattern("/*");
		ipFilterMap.addServletName("*");
		ipFilterMap.setDispatcher("REQUEST");
		ipFilterMap.setDispatcher("FORWARD");
		context.addFilterMap(ipFilterMap);

		SecurityFilter userFilter = new SecurityFilter();
		FilterDef filterDef = new FilterDef();
		filterDef.setFilter(userFilter);
		filterDef.setFilterName(SecurityFilter.FILTER_NAME);
		context.addFilterDef(filterDef);

		final FilterMap filterMap = new FilterMap();
		filterMap.setFilterName(SecurityFilter.FILTER_NAME);
		filterMap.addURLPattern("/*");
		filterMap.addServletName("*");
		filterMap.setDispatcher("REQUEST");
		filterMap.setDispatcher("FORWARD");
		context.addFilterMap(filterMap);

		// DomainProxy runs last in the chain: requests that match a configured
		// domain:port[:uri] mapping are proxied/redirected here; everything else
		// falls through the chain to the local webapp (default servlet / JSP).
		if (proxyEnable) {
			DomainProxy proxy = new DomainProxy();
			FilterDef proxyDef = new FilterDef();
			proxyDef.setFilter(proxy);
			proxyDef.setFilterName(DomainProxy.FILTER_NAME);
			context.addFilterDef(proxyDef);

			final FilterMap proxyMap = new FilterMap();
			proxyMap.setFilterName(DomainProxy.FILTER_NAME);
			proxyMap.addURLPattern("/*");
			proxyMap.addServletName("*");
			proxyMap.setDispatcher("REQUEST");
			proxyMap.setDispatcher("FORWARD");
			context.addFilterMap(proxyMap);
		}

	}

	/** Stops the server asynchronously. */
	public void stop() {
		stop(null);
	}

	/** Stops the server synchronously, invoking a callback afterwards. */
	protected void stop(Runnable callback) {
		if (tomcat == null) {
			log.warn("Tomcat is not running.");
			runningServer = null;
			if (callback != null)
				callback.run();
			return;
		}

		ExecutorService exec = lifecycleExecutor();
		// Runs the synchronous shutdown on the shared lifecycle executor.
		exec.execute(new Runnable() {
			@Override
			public void run() {
				try {
					tomcat.stop();
					log.info("Tomcat.stop() called.");

					org.apache.catalina.Server server = tomcat.getServer();
					if (server != null) {
						CountDownLatch latch = new CountDownLatch(1);
						Thread waitThread = new Thread(() -> {
							try {
								log.debug("Waiting for Tomcat server to stop...");
								server.await();
								log.debug("Tomcat server.await() returned.");
							} catch (Exception e) {
								log.error("Error during server.await(): " + e.getMessage(), e);
							} finally {
								latch.countDown();
							}
						});
						// B3 fix: daemon so a stuck server.await() can never keep
						// the JVM alive.
						waitThread.setDaemon(true);
						waitThread.start();

						boolean stopped = latch.await(30, TimeUnit.SECONDS);
						if (!stopped) {
							log.warn("Tomcat server did not stop within 30 seconds, forcing shutdown.");
							waitThread.interrupt();
						}
					}

					tomcat.destroy();
					log.info("Tomcat.destroy() called.");

					tomcat = null;

					log.info("Tomcat stopped and destroyed successfully.");
				} catch (Exception e) {
					log.error("Failed to stop Tomcat: " + e.getMessage(), e);
				} finally {
					runningServer = null;
					if (callback != null)
						callback.run();
				}
			}
		});
	}

	/** Current JVM memory usage snapshot (used/test reports). */
	public static Map<String, Long> getMemoryInfo() {
		Map<String, Long> info = new HashMap<String, Long>();
		Runtime instance = Runtime.getRuntime();
		Long totalMemory = instance.totalMemory();
		Long freeMemory = instance.freeMemory();
		Long usedMemory = (instance.totalMemory() - instance.freeMemory());
		Long maxMemory = instance.maxMemory();
		info.put("total", totalMemory);
		info.put("free", freeMemory);
		info.put("used", usedMemory);
		info.put("max", maxMemory);
		return info;
	}

	/** Pre-loads the domain mappings from the DB into the proxy cache. */
	private void loadDomainMapping() {
		CacheDriverExecutor cde = null;
		String sql = "SELECT * FROM domain_mapping_param WHERE deleted = :deleted ORDER BY created_at ASC";
		try {
			Map<String, Object> sqlParams = new HashMap<String, Object>();
			sqlParams.put("deleted", false);
			Connection conn = Db.getConnection();
			cde = new CacheDriverExecutor(conn);
			List<Map<String, Object>> list = cde.find(sql, sqlParams);
			for (Map<String, Object> item : list) {
				String domain = (String) item.get("domain");
				String port = (String) item.get("port");
				Object uriObj = item.get("uri");
				String uri = (uriObj == null) ? "" : ("" + uriObj);
				try {
					// C11 fix: one row with a broken param_value used to abort
					// the whole load; skip the bad row, keep loading the rest.
					String paramValue = (String) item.get("param_value");
					Map<String, Object> domainParams = Json.parse(paramValue);
					Map<String, Object> resMap = DomainProxy.setDomainMapping(domain, port, uri, domainParams);
					Logger.systemMark(TomcatServer.class, resMap);
				} catch (Exception rowErr) {
					log.warn("Skipped broken DomainMapping {}:{}:{}: {}", domain, port, uri, rowErr.getMessage());
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Db.closeQuietly(cde);
		}
	}

	/**
	 * Loads all {@code domain_ssl_host} rows into live SSL host configs
	 * (management API result: {@code code/msg/data}).
	 */
	public Map<String, Object> loadDomainSslHosts() {
		Map<String, Object> resMap = new HashMap<>();
		CacheDriverExecutor cde = null;
		String sql = "SELECT * FROM domain_ssl_host WHERE deleted = :deleted ORDER BY created_at ASC";
		try {
			Map<String, Object> sqlParams = new HashMap<String, Object>();
			sqlParams.put("deleted", false);
			Connection conn = Db.getConnection();
			cde = new CacheDriverExecutor(conn);
			List<Map<String, Object>> list = cde.find(sql, sqlParams);
			for (Map<String, Object> item : list) {
				String hostname = (String) item.get("hostname");
				String keystoreFile = (String) item.get("keystore_file");
				// B12 fix: rows written with the master key carry an empty
				// encrypt_key; fall back to the configured master key so they
				// still decrypt. Legacy rows keep their own stored key.
				String rowKey = (String) item.get("encrypt_key");
				String masterKey = sslEncryptKeyConfig();
				String decryptKey = (CommonTools.isBlank(rowKey) && masterKey != null && !masterKey.isEmpty())
						? masterKey : rowKey;
				String keystorePass = decryptKeystorePass(decryptKey,
						(String) item.get("keystore_pass"));
				String keyAlias = (String) item.get("key_alias");
				try {
					addSslHost(false, hostname, keystoreFile, keystorePass, keyAlias);
				} catch (Exception e) {
					log.warn("Failed to add SSLHost {}: {}", hostname, e.getMessage(), e);
				}
			}
			resMap.put("code", 1);
			resMap.put("msg", String.format("%d DomainSslHost(s) loaded.", list.size()));
			return resMap;
		} catch (Exception e) {
			log.warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to load DomainSslHosts.");
			return resMap;
		} finally {
			Db.closeQuietly(cde);
		}
	}

	public synchronized static Map<String, Object> createDomainSslHost(String hostname, String keystoreFile,
			String keystorePass, String keyAlias) {
		Map<String, Object> resMap = new HashMap<String, Object>();
		if (CommonTools.isBlank(hostname)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'hostname' is required.");
			return resMap;
		}
		if (CommonTools.isBlank(keystoreFile)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'keystoreFile' is required.");
			return resMap;
		}
		if (CommonTools.isBlank(keystorePass)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'keystorePass' is required.");
			return resMap;
		}
		if (CommonTools.isBlank(keyAlias)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'keyAlias' is required.");
			return resMap;
		}

		String sql = "INSERT INTO domain_ssl_host (id,hostname,keystore_file,keystore_pass,key_alias,encrypt_mode,encrypt_key,created_at,updated_at,deleted_at,deleted) VALUES (:id,:hostname,:keystore_file,:keystore_pass,:key_alias,:encrypt_mode,:encrypt_key,:created_at,:updated_at,:deleted_at,:deleted)";
		CacheDriverExecutor cde = null;
		try {
			hostname = hostname.toLowerCase();
			if (getDomainSslHost(hostname) != null) {
				resMap.put("code", 0);
				resMap.put("msg", String.format("The DomainSslHost %s already exists.", hostname));
				return resMap;
			}
			String encryptMode = "AES";
			// B12 fix: when SSL.keystoreEncryptKey is configured, use it as the
			// master AES key and store NOTHING in the encrypt_key column (a
			// plaintext key next to its ciphertext made the DB dump self-decrypting).
			String masterKey = sslEncryptKeyConfig();
			boolean useMaster = masterKey != null && !masterKey.isEmpty();
			String encryptKey = useMaster ? "" : CommonTools.generateId(256);
			Timestamp currentTime = new Clock().getSqlTimestamp();
			Connection conn = Db.getConnection();
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", CommonTools.generateId(16));
			params.put("hostname", hostname);
			params.put("keystore_file", keystoreFile);
			params.put("keystore_pass", encryptKeystorePass(useMaster ? masterKey : encryptKey, keystorePass));
			params.put("key_alias", keyAlias);
			params.put("encrypt_mode", encryptMode);
			params.put("encrypt_key", encryptKey);
			params.put("created_at", currentTime);
			params.put("updated_at", currentTime);
			params.put("deleted_at", null);
			params.put("deleted", false);
			cde = new CacheDriverExecutor(conn);
			cde.execute(sql, params);
			// if (runningServer != null) {
			// runningServer.addSslHost(hostname, keystoreFile, keystorePass, keyAlias);
			// }
			resMap.put("code", 1);
			resMap.put("msg", String.format("DomainSslHost %s created successfully.", hostname));
			return resMap;
		} catch (Exception e) {
			LoggerFactory.getLogger(TomcatServer.class).warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to create DomainSslHost.");
			return resMap;
		} finally {
			Db.closeQuietly(cde);
		}
	}

	public synchronized static Map<String, Object> updateDomainSslHost(String hostname, String keystoreFile,
			String keystorePass, String keyAlias) {
		Map<String, Object> resMap = new HashMap<String, Object>();
		if (CommonTools.isBlank(hostname)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'hostname' is required.");
			return resMap;
		}
		if (CommonTools.isBlank(keystoreFile)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'keystoreFile' is required.");
			return resMap;
		}
		if (CommonTools.isBlank(keystorePass)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'keystorePass' is required.");
			return resMap;
		}
		if (CommonTools.isBlank(keyAlias)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'keyAlias' is required.");
			return resMap;
		}

		String sql = "UPDATE domain_ssl_host SET keystore_file = :keystore_file,keystore_pass = :keystore_pass,key_alias = :key_alias,encrypt_mode = :encrypt_mode,encrypt_key = :encrypt_key,updated_at = :updated_at WHERE id = :id";
		CacheDriverExecutor cde = null;
		try {
			hostname = hostname.toLowerCase();
			Map<String, Object> existing = getDomainSslHost(hostname);
			if (existing == null) {
				resMap.put("code", 0);
				resMap.put("msg", String.format("The DomainSslHost %s does not exist.", hostname));
				return resMap;
			}
			// Reuse the row's own AES key; assign a fresh one only for legacy
			// plaintext rows (written before encryption was supported).
			// B12 fix: when SSL.keystoreEncryptKey is configured it becomes the
			// master key and the encrypt_key column stores nothing.
			String masterKey = sslEncryptKeyConfig();
			boolean useMaster = masterKey != null && !masterKey.isEmpty();
			String encryptKey = useMaster ? "" : (String) existing.get("encrypt_key");
			String encryptMode = (String) existing.get("encrypt_mode");
			if (!useMaster && CommonTools.isBlank(encryptKey)) {
				encryptKey = CommonTools.generateId(256);
				encryptMode = "AES";
			}
			Timestamp currentTime = new Clock().getSqlTimestamp();
			Connection conn = Db.getConnection();
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", existing.get("id"));
			params.put("keystore_file", keystoreFile);
			params.put("keystore_pass", encryptKeystorePass(useMaster ? masterKey : encryptKey, keystorePass));
			params.put("key_alias", keyAlias);
			params.put("encrypt_mode", encryptMode);
			params.put("encrypt_key", encryptKey);
			params.put("updated_at", currentTime);
			cde = new CacheDriverExecutor(conn);
			cde.execute(sql, params);
			resMap.put("code", 1);
			resMap.put("msg", String.format("DomainSslHost %s updated successfully.", hostname));
			return resMap;
		} catch (Exception e) {
			LoggerFactory.getLogger(TomcatServer.class).warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to update DomainSslHost.");
			return resMap;
		} finally {
			Db.closeQuietly(cde);
		}
	}

	/** Management API: removes a domain SSL host by hostname (soft delete). */
	public synchronized static Map<String, Object> removeDomainSslHost(String hostname) {
		Map<String, Object> resMap = new HashMap<String, Object>();
		if (CommonTools.isBlank(hostname)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'hostname' is required.");
			return resMap;
		}

		String sql = "UPDATE domain_ssl_host SET deleted = :deleted,deleted_at = :deleted_at WHERE id = :id";
		CacheDriverExecutor cde = null;
		try {
			hostname = hostname.toLowerCase();
			Map<String, Object> existing = getDomainSslHost(hostname);
			if (existing == null) {
				resMap.put("code", 0);
				resMap.put("msg", String.format("The DomainSslHost %s does not exist.", hostname));
				return resMap;
			}
			Timestamp currentTime = new Clock().getSqlTimestamp();
			Connection conn = Db.getConnection();
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", existing.get("id"));
			params.put("deleted", true);
			params.put("deleted_at", currentTime);
			cde = new CacheDriverExecutor(conn);
			cde.execute(sql, params);
			resMap.put("code", 1);
			resMap.put("msg", String.format("DomainSslHost %s removed successfully.", hostname));
			return resMap;
		} catch (Exception e) {
			LoggerFactory.getLogger(TomcatServer.class).warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to remove DomainSslHost.");
			return resMap;
		} finally {
			Db.closeQuietly(cde);
		}
	}

	/** Management API: reads one domain SSL host. */
	public static Map<String, Object> queryDomainSslHost(String hostname) {
		Map<String, Object> resMap = new HashMap<String, Object>();
		String msg = "";
		// B10 fix: null/blank hostname used to NPE at toLowerCase().
		if (CommonTools.isBlank(hostname)) {
			resMap.put("code", 0);
			resMap.put("msg", "The param 'hostname' is required.");
			return resMap;
		}
		try {
			hostname = hostname.toLowerCase();
			Map<String, Object> result = getDomainSslHost(hostname);
			if (result == null) {
				resMap.put("code", 0);
				resMap.put("msg", String.format("The DomainSslHost %s does not exist.", hostname));
			} else {
				result.remove("keystore_pass");
				result.remove("encrypt_key");
				msg += String.format("Id: %s", result.get("id")) + System.lineSeparator();
				msg += String.format("Hostname: %s", result.get("hostname")) + System.lineSeparator();
				msg += String.format("KeystoreFile: %s", result.get("keystore_file")) + System.lineSeparator();
				msg += String.format("KeyAlias: %s", result.get("key_alias")) + System.lineSeparator();
				msg += String.format("CreatedAt: %s", result.get("created_at")) + System.lineSeparator();
				msg += String.format("UpdatedAt: %s", result.get("updated_at")) + System.lineSeparator();
				resMap.put("code", 1);
				resMap.put("msg", msg);
			}
			return resMap;
		} catch (Exception e) {
			LoggerFactory.getLogger(TomcatServer.class).warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", String.format("Failed to query DomainSslHost %s.", hostname));
			return resMap;
		}
	}

	/** Management API: lists every domain SSL host. */
	public static Map<String, Object> queryDomainSslHosts() {
		Map<String, Object> resMap = new HashMap<String, Object>();
		String msg = "";
		try {
			List<Map<String, Object>> list = getDomainSslHosts();
			int size = list.size();
			if (size == 0) {
				resMap.put("code", 0);
				resMap.put("msg", "No domain_ssl_host records found.");
				return resMap;
			}
			for (int i = 0; i < size; i++) {
				Map item = list.get(i);
				msg += String.format("%s) %s : %s (alias=%s)", i + 1, item.get("hostname"), item.get("keystore_file"),
						item.get("key_alias")) + System.lineSeparator();
			}
			resMap.put("code", 1);
			resMap.put("msg", msg);
			return resMap;
		} catch (Exception e) {
			LoggerFactory.getLogger(TomcatServer.class).warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to query DomainSslHosts.");
			return resMap;
		}
	}

	/** Loads one domain SSL host row from the DB. */
	private static Map<String, Object> getDomainSslHost(String hostname) throws Exception {
		CacheDriverExecutor cde = null;
		String sql = "SELECT * FROM domain_ssl_host WHERE hostname = :hostname AND deleted = :deleted";
		try {
			Connection conn = Db.getConnection();
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("hostname", hostname);
			params.put("deleted", false);
			cde = new CacheDriverExecutor(conn);
			return cde.first(sql, params);
		} finally {
			Db.closeQuietly(cde);
		}
	}

	/** Loads all domain SSL host rows from the DB. */
	private static List<Map<String, Object>> getDomainSslHosts() throws Exception {
		CacheDriverExecutor cde = null;
		String sql = "SELECT * FROM domain_ssl_host WHERE deleted = :deleted ORDER BY created_at ASC";
		try {
			Connection conn = Db.getConnection();
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("deleted", false);
			cde = new CacheDriverExecutor(conn);
			return cde.find(sql, params);
		} finally {
			Db.closeQuietly(cde);
		}
	}

	// ------------------------------------------------------------------
	// keystorePass encryption (per-row AES key, following the DomainAuth pattern)
	// ------------------------------------------------------------------

	/** Encrypts a keystore password for storage. */
	private static String encryptKeystorePass(String encryptKey, String plain) {
		try {
			byte[] encrypted = CipherTools.AESEncrypt(encryptKey, plain.getBytes(BaseFile.CHARSET));
			return Base64Tools.encodeLines(encrypted);
		} catch (Exception e) {
			Logger.systemError(TomcatServer.class, "Failed to encrypt keystorePass, stored as-is: {}", e.getMessage(),
					e);
			return plain;
		}
	}

	/** Decrypts a stored keystore password. */
	private static String decryptKeystorePass(String encryptKey, String stored) {
		if (CommonTools.isBlank(encryptKey) || CommonTools.isBlank(stored)) {
			return stored; // legacy plaintext row
		}
		try {
			byte[] decrypted = CipherTools.AESDecrypt(encryptKey, Base64Tools.decodeLines(stored));
			return new String(decrypted, BaseFile.CHARSET);
		} catch (Exception e) {
			// Legacy plaintext row (written before encryption was enabled) or wrong key:
			// return the stored value unchanged so existing hosts keep working.
			return stored;
		}
	}

}
