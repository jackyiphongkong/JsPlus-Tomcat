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
import java.util.ArrayList;
import java.util.stream.Collectors;

public class TomcatScriptConsole {

	private static final Logger log = LoggerFactory.getLogger(TomcatScriptConsole.class);

	private static final Map<Integer, ScriptConsole> CONSOLES_POOL = new ConcurrentHashMap<Integer, ScriptConsole>();
	private static final Map<Long, ScriptConsole> CONSOLES_USING_MARK = new ConcurrentHashMap<Long, ScriptConsole>();
	private static final Map<Long, Integer> CONSOLES_THREAD_MARK = new ConcurrentHashMap<Long, Integer>();

	private static int cacheSeconds = 60;

	private static int poolSize = 1000;

	private static int currentPoolSize = 0;

	private static long freedDelayMs = 10L;

	private static String defaultEngineName = "graalvm.js";

	private static String engineName = "graalvm.js";

	private static ScriptConsole MasterConsole = null;

	public synchronized static boolean isLoadedScriptEngine() {
		return MasterConsole != null;
	}

	public synchronized static void initScriptConsole() throws Exception {
		initScriptConsole(getEngineName());
	}

	public synchronized static void initScriptConsole(String initEngineName) throws Exception {
		if (MasterConsole == null) {
			ConfigProperties consoleVariables = new ConfigProperties();
			if (CommonTools.isBlank(initEngineName)) {
				engineName = defaultEngineName;
			}
			consoleVariables.putAndReturnSlef("ScriptEngine", engineName);
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

	public synchronized static double usage() {
		int idleSize = list().size();
		double usage = (currentPoolSize - idleSize) / (currentPoolSize * 1.0D);
		return usage;
	}

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

	public synchronized static void freed() {
		try {
			clear();
			long tid = Thread.currentThread().getId();
			final ScriptConsole sc = CONSOLES_USING_MARK.get(tid);
			if (sc != null) {
				Integer useIndex = CONSOLES_THREAD_MARK.get(tid);
				CONSOLES_THREAD_MARK.remove(tid);
				CONSOLES_USING_MARK.remove(tid);
				Executors.newSingleThreadExecutor().execute(new Runnable() {
					@Override 
					public void run(){
						try{
							Thread.sleep(getFreedDelayMs());
						}catch(Exception e){
							Logger.systemError(TomcatScriptConsole.class,e.getMessage(),e);
						}finally{
							CONSOLES_POOL.put(useIndex, sc);
						}
						// Logger.systemInfo(TomcatScriptConsole.class, "After freed pool size: {}",list().size());
					}
				});
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	public static void put(Object params) throws Exception {
		getConsole().getContext().put(params);
	}

	public static void clear() throws Exception {
		getConsole().getContext().remove();
	}

	public static Object get() throws Exception {
		return getConsole().getContext().get();
	}

	public static Object eval(File scriptPath) throws Exception {
		return eval(scriptPath, getCacheSeconds());
	}

	public static Object eval(String scriptPath) throws Exception {
		return eval(new File(scriptPath), getCacheSeconds());
	}

	public static Object eval(String scriptPath, int cacheLifeSec) throws Exception {
		return eval(new File(scriptPath), cacheLifeSec);
	}

	public static Object eval(File scriptPath, int cacheLifeSec) throws Exception {
		String path = scriptPath.getAbsolutePath();
		String cacheKey = CommonTools.md5(path);
		String script = (String) Cache.get(cacheKey);
		if (CommonTools.isBlank(script)) {
			script = FileTools.text(path);
			Cache.set(cacheKey, script, cacheLifeSec);
		}
		return getConsole().evalScript(script);
	}

	public static Object evalScript(String script) throws Exception {
		return getConsole().evalScript(script);
	}

	public static boolean checkExists(File file) {
		String cacheKey = "Exists_" + CommonTools.md5(file.getAbsolutePath());
		Boolean exists = (Boolean) Cache.get(cacheKey);
		if (exists == null) {
			exists = file.exists();
			Cache.set(cacheKey, exists, getCacheSeconds());
		}
		return exists;
	}

	public static ScriptConsole getMasterConsole() {
		return MasterConsole;
	}

	public static void setCacheSeconds(int cacheSeconds) {
		TomcatScriptConsole.cacheSeconds = cacheSeconds;
	}

	public static int getCacheSeconds() {
		return TomcatScriptConsole.cacheSeconds;
	}

	public static void setFreedDelayMs(long freedDelayMs){
		TomcatScriptConsole.freedDelayMs = freedDelayMs;
	}

	public static long getFreedDelayMs(){
		return TomcatScriptConsole.freedDelayMs;
	}	

	public static void setPoolSize(int poolSize) {
		TomcatScriptConsole.poolSize = poolSize;
	}

	public static int getPoolSize() {
		return poolSize;
	}

	public static void setEngineName(String engineName) {
		TomcatScriptConsole.engineName = engineName;
	}

	public static String getEngineName() {
		return TomcatScriptConsole.engineName;
	}

	public static String getAppScriptPath() {
		return System.getenv("JS_PLUS_PATH") + "/domain/";
	}

	// public static Object getDomainParams(String domain, String port) throws Exception {
	// 	String scriptPath = String.format("%s_%s.js", domain, port);
	// 	return getDomainParams(scriptPath);
	// }

	public static Object getDomainParams(String customScriptFile) throws Exception {
		try {
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

	public static Object getResponseFilter(String domain, String port) throws Exception {
		String scriptPath = String.format("%s_%s.js", domain, port);
		return getResponseFilter(scriptPath);
	}

	public static Object getResponseFilter(String customScriptFile) throws Exception {
		try {
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

}