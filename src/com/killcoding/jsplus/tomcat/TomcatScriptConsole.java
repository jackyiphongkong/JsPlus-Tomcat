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

import java.util.List;
import com.killcoding.log.Logger;
import com.killcoding.log.LoggerFactory;
import com.killcoding.tool.CommonTools;
import com.killcoding.tool.FileTools;
import java.io.File;
import com.killcoding.cache.Cache;
import java.util.Map;
import com.killcoding.jsplus.ScriptConsole;
import com.killcoding.tool.ConfigProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Script-engine facade used by the embedded Tomcat: a fixed pool of
 * {@link ScriptConsole} instances (one per request thread) with a master
 * console that owns the data source, script-engine init and JVM-wide settings.
 * <p>
 * Every request acquires a pooled console via {@link #getConsole()}, runs
 * domain/response scripts through {@link #eval(File, int)} / {@code evalScript}
 * and returns it via {@link #freed()} (delayed by {@code freedDelayMs} on a
 * shared daemon scheduler). The pool size is configurable and grows on demand
 * up to {@code poolSize}.
 */
public class TomcatScriptConsole {

	private static final Logger log = LoggerFactory.getLogger(TomcatScriptConsole.class);

	private static final Map<Integer, ScriptConsole> CONSOLES_POOL = new ConcurrentHashMap<Integer, ScriptConsole>();
	private static final Map<Long, ScriptConsole> CONSOLES_USING_MARK = new ConcurrentHashMap<Long, ScriptConsole>();
	private static final Map<Long, Integer> CONSOLES_THREAD_MARK = new ConcurrentHashMap<Long, Integer>();

	/** Shared daemon scheduler for delayed console return (freed): fixed-size,
	 *  non-leaking (never grows per call), and never blocks JVM exit. */
	private static final ScheduledExecutorService FREED_SCHEDULER = Executors.newScheduledThreadPool(
			Math.max(2, Runtime.getRuntime().availableProcessors()),
			r -> {
				Thread t = new Thread(r, "jsplus-tomcat-console-freed");
				t.setDaemon(true);
				return t;
			});

	private static int cacheSeconds = 60;

	private static int poolSize = 1000;

	private static int currentPoolSize = 0;

	private static long freedDelayMs = 10L;

	private static String defaultEngineName = "graalvm.js";

	private static String engineName = "graalvm.js";

	private static ScriptConsole MasterConsole = null;

	/** Whether the master console (and thus the script engine) is initialized. */
	public synchronized static boolean isLoadedScriptEngine() {
		return MasterConsole != null;
	}

	/**
	 * Initializes the master console with the configured engine and pre-warms
	 * the console pool (at least 10 consoles, growing to {@code poolSize}).
	 */
	public synchronized static void initScriptConsole() throws Exception {
		initScriptConsole(getEngineName());
	}

	/**
	 * Initializes the master console with the given engine name, then builds the
	 * initial console pool. No-op when already initialized.
	 *
	 * @param initEngineName engine id (e.g. {@code graalvm.js}); blank falls back
	 *                        to the default engine
	 */
	public synchronized static void initScriptConsole(String initEngineName) throws Exception {
		if (MasterConsole == null) {
			ConfigProperties consoleVariables = new ConfigProperties();
			// B6 fix: honour the passed engine name — the old code ignored a
			// non-blank argument and always used the field's previous value.
			if (!CommonTools.isBlank(initEngineName)) {
				engineName = initEngineName;
			} else if (CommonTools.isBlank(engineName)) {
				engineName = defaultEngineName;
			}
			consoleVariables.putAndReturnSelf("ScriptEngine", engineName);
			MasterConsole = new ScriptConsole();
			MasterConsole.setUseScriptEnv();
			MasterConsole.setConsoleVariables(consoleVariables);
			MasterConsole.initScriptEngineManager();
			currentPoolSize = getPoolSize();

			if (currentPoolSize < 10)
				currentPoolSize = 10;

			for (int i = 0; i < currentPoolSize; i++) {
				CONSOLES_POOL.put(i, MasterConsole.newBuilder());
			}
		}
	}

	/**
	 * Acquires a console for the current thread (one console per thread, tracked
	 * by thread id). Grows the pool when {@code poolSize} was raised; throws when
	 * every console is in use.
	 *
	 * @return the console bound to the current thread
	 * @throws Exception when the pool is exhausted
	 */
	private synchronized static ScriptConsole getConsole() throws Exception {
		int newPoolSoze = getPoolSize();
		if (newPoolSoze > currentPoolSize) {
			Logger.systemMark(TomcatScriptConsole.class, "TomcatScriptConsole changed pool size to '{}'.",
					newPoolSoze);
			for (int i = currentPoolSize; i < newPoolSoze; i++) {
				CONSOLES_POOL.put(i, MasterConsole.newBuilder());
			}
			currentPoolSize = newPoolSoze;
		}
		long tid = Thread.currentThread().getId();
		Integer threadMark = CONSOLES_THREAD_MARK.get(tid);
		if (threadMark == null) {
			List<Integer> list = list();
			int idleSize = list.size();
			double usage = usage();
			if (usage >= 0.8D) {
				log.warn("The console pool usage has reached {} ({}/{}).", "80%", (currentPoolSize - idleSize),
						currentPoolSize);
			}
			// log.info("Idle Pool Size: {}", idleSize);
			if (idleSize > 0) {
				Integer first = list.get(0);
				ScriptConsole sc = CONSOLES_POOL.get(first);
				CONSOLES_USING_MARK.put(tid, sc);
				CONSOLES_THREAD_MARK.put(tid, first);
				CONSOLES_POOL.remove(first);
				// log.mark("first: {} -> {}",first,tid);
			} else {
				throw new Exception("Full Console Pool.");
			}
		}
		return CONSOLES_USING_MARK.get(tid);
	}

	/** Pool usage ratio (0.0 = all idle, 1.0 = all in use). */
	public synchronized static double usage() {
		int idleSize = list().size();
		double usage = (currentPoolSize - idleSize) / (currentPoolSize * 1.0D);
		return usage;
	}

	/** Idle console indices, ascending. */
	private synchronized static List<Integer> list() {
		List<Integer> list = new ArrayList<Integer>();
		List<Integer> klist = CONSOLES_POOL.keySet()
				.stream()
				.sorted()
				.collect(Collectors.toList());
		for (Object k : klist) {
			list.add((Integer) k);
		}
		// log.mark("list: {}",list);
		return list;
	}

	/**
	 * Returns the current thread's console to the pool after
	 * {@code freedDelayMs}; no-op when the thread never held a console.
	 */
	public synchronized static void freed() {
		try {
			long tid = Thread.currentThread().getId();
			// C10 fix: only clear the context when this thread actually holds a
			// console. The old code called clear() unconditionally, which made
			// freed() take a fresh console (and even throw "Full Console Pool")
			// for threads that never held one.
			if (CONSOLES_USING_MARK.get(tid) != null) {
				clear();
			}
			final ScriptConsole sc = CONSOLES_USING_MARK.get(tid);
			if (sc != null) {
				final Integer useIndex = CONSOLES_THREAD_MARK.get(tid);
				CONSOLES_THREAD_MARK.remove(tid);
				CONSOLES_USING_MARK.remove(tid);
				// Delayed return: semantically identical to "sleep(freedDelayMs)
				// then put back", but without spawning one thread per call and
				// without occupying a pool thread during the delay.
				long delayMs = getFreedDelayMs();
				// log.info("Console freed scheduled: index={}, by={}, delay={}ms", useIndex,
				// 		Thread.currentThread().getName(), delayMs);
				FREED_SCHEDULER.schedule(new Runnable() {
					@Override
					public void run() {
						try {
							CONSOLES_POOL.put(useIndex, sc);
							// log.info("Console returned to pool: index={}, by={}", useIndex,
							// 		Thread.currentThread().getName());
							log.info("After freed pool size: {}",list().size());
						} catch (Exception e) {
							log.info(e.getMessage(), e);
						}
					}
				}, delayMs, TimeUnit.MILLISECONDS);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	/** Puts a value into the current thread's console context. */
	public static void put(Object params) throws Exception {
		getConsole().getContext().put(params);
	}

	/** Clears the current thread's console context. */
	public static void clear() throws Exception {
		getConsole().getContext().remove();
	}

	/** Reads the current thread's console context. */
	public static Object get() throws Exception {
		return getConsole().getContext().get();
	}

	/** Evaluates a script file (content cached for {@link #getCacheSeconds()}). */
	public static Object eval(File scriptPath) throws Exception {
		return eval(scriptPath, getCacheSeconds());
	}

	/** Evaluates a script file by path (content cached). */
	public static Object eval(String scriptPath) throws Exception {
		return eval(new File(scriptPath), getCacheSeconds());
	}

	/** Evaluates a script file by path with an explicit cache lifetime. */
	public static Object eval(String scriptPath, int cacheLifeSec) throws Exception {
		return eval(new File(scriptPath), cacheLifeSec);
	}

	/**
	 * Evaluates a script file with an explicit cache lifetime: the file content
	 * is cached under an MD5 key of its absolute path.
	 */
	public static Object eval(File scriptPath, int cacheLifeSec) throws Exception {
		String path = scriptPath.getAbsolutePath();
		String cacheKey = CommonTools.md5(path);
		String script = null;
		// D4 fix (Core 6.1.163): Cache.set treats life==0 as "never expire",
		// while this project uses 0 = do not cache. Skip the cache entirely
		// for non-positive lifetimes so a stale hit from an earlier positive
		// TTL can never happen.
		if (cacheLifeSec > 0) {
			script = (String) Cache.get(cacheKey);
		}
		if (CommonTools.isBlank(script)) {
			script = FileTools.text(path);
			if (cacheLifeSec > 0) {
				Cache.set(cacheKey, script, cacheLifeSec);
			}
		}
		return getConsole().evalScript(script);
	}

	/** Evaluates a raw script string on the current thread's console. */
	public static Object evalScript(String script) throws Exception {
		return getConsole().evalScript(script);
	}

	/** Cached {@code file.exists()} check (cache lifetime = cacheSeconds). */
	public static boolean checkExists(File file) {
		String cacheKey = "Exists_" + CommonTools.md5(file.getAbsolutePath());
		Boolean exists = null;
		// D4 fix (Core 6.1.163): Cache.set treats life==0 as "never expire";
		// 0 = do not cache in this project, so non-positive lifetimes skip the
		// cache on both read and write.
		int cacheLifeSec = getCacheSeconds();
		if (cacheLifeSec > 0) {
			exists = (Boolean) Cache.get(cacheKey);
		}
		if (exists == null) {
			exists = file.exists();
			if (cacheLifeSec > 0) {
				Cache.set(cacheKey, exists, cacheLifeSec);
			}
		}
		return exists;
	}

	/** The master console — owns the data source and engine init (singleton). */
	public static ScriptConsole getMasterConsole() {
		return MasterConsole;
	}

	/** Sets the script-file cache lifetime (seconds). */
	public static void setCacheSeconds(int cacheSeconds) {
		TomcatScriptConsole.cacheSeconds = cacheSeconds;
	}

	/** Returns the script-file cache lifetime (seconds). */
	public static int getCacheSeconds() {
		return TomcatScriptConsole.cacheSeconds;
	}

	/** Sets how long a freed console stays out of the pool before return (ms). */
	public static void setFreedDelayMs(long freedDelayMs){
		TomcatScriptConsole.freedDelayMs = freedDelayMs;
	}

	/** Returns the freed-console return delay (ms). */
	public static long getFreedDelayMs(){
		return TomcatScriptConsole.freedDelayMs;
	}	

	/** Sets the maximum console pool size (grows on demand up to this). */
	public static void setPoolSize(int poolSize) {
		TomcatScriptConsole.poolSize = poolSize;
	}

	/** Returns the configured maximum console pool size. */
	public static int getPoolSize() {
		return poolSize;
	}

	/** Actual number of consoles currently in the pool (vs the configured
	 *  poolSize) — a hard number for tests to confirm the pool returns to
	 *  full after traffic. */
	public static int getConsolePoolSize() {
		return CONSOLES_POOL.size();
	}

	/** Sets the script-engine id used to init the master console. */
	public static void setEngineName(String engineName) {
		TomcatScriptConsole.engineName = engineName;
	}

	/** Returns the script-engine id (e.g. {@code graalvm.js}). */
	public static String getEngineName() {
		return TomcatScriptConsole.engineName;
	}

	/** Base folder holding per-domain scripts (JS_PLUS_PATH/domain/). */
	public static String getAppScriptPath() {
		return System.getenv("JS_PLUS_PATH") + "/domain/";
	}

	// public static Object getDomainParams(String domain, String port) throws Exception {
	// 	String scriptPath = String.format("%s_%s.js", domain, port);
	// 	return getDomainParams(scriptPath);
	// }

	/**
	 * Evaluates a domain script's {@code getParams()} and returns the mapping
	 * config. The script file is resolved inside the domain folder and the
	 * console is returned to the pool afterwards.
	 *
	 * @param customScriptFile bare script file name (path traversal rejected)
	 * @return the {@code DomainMapping.getParams()} result, or {@code null}
	 *         when the file is missing or rejected
	 */
	public static Object getDomainParams(String customScriptFile) throws Exception {
		try {
			// C8 fix: reject path traversal / absolute paths in scriptFile so a
			// mapping cannot evaluate a script outside the JS_PLUS_PATH/domain folder.
			if (!isSafeScriptFile(customScriptFile)) {
				log.warn("Invalid scriptFile (path traversal rejected): {}", customScriptFile);
				return null;
			}
			String scriptPath = String.format("%s/%s", getAppScriptPath(),customScriptFile);
			File scrptFile = new File(scriptPath);
			if (checkExists(scrptFile)) {
				log.debug("Loaded: {}", scriptPath);
				eval(scrptFile);
				String method = "getParams";
				return evalScript(String.format("(function(){return DomainMapping.%s();})();", method));
			} else {
				log.warn("Missing: {}", scriptPath);
				return null;
			}
		} finally {
			freed();
		}
	}	

	/**
	 * Evaluates a domain script's {@code filter()} response-filter function for
	 * a domain:port pair. Console is returned to the pool afterwards.
	 *
	 * @return the {@code DomainMapping.filter()} result, or {@code null} when
	 *         the script is missing or rejected
	 */
	public static Object getResponseFilter(String domain, String port) throws Exception {
		String scriptPath = String.format("%s_%s.js", domain, port);
		return getResponseFilter(scriptPath);
	}

	/**
	 * Evaluates a domain script's {@code filter()} response-filter function for
	 * a custom script file. Console is returned to the pool afterwards.
	 *
	 * @return the {@code DomainMapping.filter()} result, or {@code null} when
	 *         the script is missing or rejected
	 */
	public static Object getResponseFilter(String customScriptFile) throws Exception {
		try {
			// C8 fix: reject path traversal / absolute paths in scriptFile.
			if (!isSafeScriptFile(customScriptFile)) {
				log.warn("Invalid scriptFile (path traversal rejected): {}", customScriptFile);
				return null;
			}
			String scriptPath = String.format("%s/%s", getAppScriptPath(),customScriptFile);
			File scrptFile = new File(scriptPath);
			if (checkExists(scrptFile)) {
				log.debug("Loaded: {}", scriptPath);
				eval(scrptFile);
				String method = "filter";
				return evalScript(String.format("(function(){return DomainMapping.%s();})();", method));
			} else {
				log.warn("Missing: {}", scriptPath);
				return null;
			}
		} finally {
			freed();
		}
	}	



	/**
	 * Evaluates a domain script's {@code preRequest()} hook — called on the way
	 * out to the backend, before any upstream HTTP request is sent. The script
	 * returns {@code null} / {@code undefined} to continue normal proxying, or a
	 * {@code Map} ({@code {status, headers:{...}, body}}) to short-circuit and
	 * return a stub / cached response directly.
	 *
	 * @return the {@code DomainMapping.preRequest()} result, or {@code null}
	 *         when the script is missing, rejected, or the hook returns null
	 */
	public static Object getRequestPreHook(String customScriptFile) throws Exception {
		try {
			if (!isSafeScriptFile(customScriptFile)) {
				log.warn("Invalid scriptFile (path traversal rejected): {}", customScriptFile);
				return null;
			}
			String scriptPath = String.format("%s/%s", getAppScriptPath(), customScriptFile);
			File scrptFile = new File(scriptPath);
			if (checkExists(scrptFile)) {
				log.debug("Loaded: {}", scriptPath);
				eval(scrptFile);
				return evalScript("(function(){return DomainMapping.preRequest ? DomainMapping.preRequest() : null;})();");
			} else {
				log.warn("Missing: {}", scriptPath);
				return null;
			}
		} finally {
			freed();
		}
	}

	/** C8: a scriptFile must be a bare relative file name inside the domain
	 *  folder — no path separators, no "..", no absolute path, no drive letter. */
	private static boolean isSafeScriptFile(String scriptFile) {
		if (scriptFile == null || scriptFile.isEmpty()) {
			return false;
		}
		if (scriptFile.contains("..")) {
			return false;
		}
		if (scriptFile.startsWith("/") || scriptFile.startsWith("\\")) {
			return false;
		}
		if (scriptFile.contains("/") || scriptFile.contains("\\")) {
			return false;
		}
		if (scriptFile.matches("^[A-Za-z]:.*")) {
			return false;
		}
		return true;
	}

}