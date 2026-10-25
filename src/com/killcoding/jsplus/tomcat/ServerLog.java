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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import com.killcoding.cache.CacheArray;
import com.killcoding.cache.CacheArrayFilter;
import com.killcoding.ext.IndexMap;
import com.killcoding.log.LoggerFactory;
import com.killcoding.tool.CodeEscape;
import com.killcoding.tool.CommonTools;
import com.killcoding.tool.ConfigProperties;
import com.killcoding.tool.FileTools;

import jakarta.servlet.http.HttpServletRequest;

public class ServerLog {

    private static CacheArray webLogCacheArray = null;

    private static ConfigProperties config = null;

    public ServerLog() {
        super();
    }

    // Called once during server startup (TomcatServer config load); no concurrent writes.
    public static void setParams(Map params) {
        config = new ConfigProperties();
        config.putAllAndReturnSlef(params);
    }

    public synchronized static boolean writeWebLog(String domain, String port, String uri, String type, long seq,
            String qs, Object headers,
            Object params,String remoteAddr) {
        
        boolean webLogEnable = config.getBoolean("Enable", false);

        if (!webLogEnable)
            return false;

        type = (type + "").toUpperCase();

        String typeParam = config.getString("Type", null);

        if (typeParam != null) {
            List<String> typeParams = Arrays.asList(typeParam.split(","));

            if (typeParams.indexOf(type) < 0)
                return false;
        }

        final String header = String.format(
                "ID,THREAD_NAME,THREAD_ID,LOG_DATE,LOG_HOUR,LOG_MI,LOG_SEC,LOG_MS,LOG_HOST,LOG_DOMAIN,LOG_PORT,LOG_URI,LOG_TYPE,LOG_SEQ,LOG_QS,LOG_HEADER,LOG_PARAMS,LOG_REMOTE_ADDR%s",
                System.lineSeparator());
        final DateFormat df = new SimpleDateFormat("yyyyMMdd");
        final DateFormat dtf = new SimpleDateFormat("yyyyMMdd,HH,mm,ss,SSS");

        String folderPath = config.getString("Folder");
        final long maxFileSize = config.getFileSize("MaxFileSize", 1024 * 1024 * 10L);
        final int archiveDays = config.getInteger("ArchiveDays", 31);
        int paramMaxLength = config.getInteger("ParamMaxLength", 20);

        if (webLogCacheArray == null) {

            webLogCacheArray = new CacheArray();

            long logTimer = config.getLong("LogTimer", 1000L);
            webLogCacheArray.filter(new CacheArrayFilter(0L, logTimer) {
                @Override
                public void executeBatch(Integer index, List batch) {
                    final StringBuilder sbf = new StringBuilder();
                    for (Object item : batch) {
                        sbf.append(item);
                    }
                    try {
                        String msg = sbf.toString();
                        java.util.Date currentTime = new java.util.Date();
                        String dateStr = df.format(currentTime);
                        File webLogFile = null;
                        if (CommonTools.isBlank(folderPath)) {
                            webLogFile = new File(String.format("%s/WebLog/%s.csv",
                                    CommonTools.getJarPath(ServerLog.class), dateStr));
                        } else {
                            webLogFile = new File(String.format("%s/%s.csv", folderPath, dateStr));
                        }
                        File webLogFolder = new File(webLogFile.getParent());

                        if (webLogFolder.exists()) {
                            if (!webLogFolder.canWrite())
                                throw new IOException(String.format("Can not write to log folder '%s'",
                                        webLogFolder.getAbsolutePath()));
                        } else {
                            webLogFolder.mkdirs();
                        }

                        if (webLogFile.exists()) {
                            if (!webLogFile.canWrite())
                                throw new IOException(
                                        String.format("Can not write to log file '%s'", webLogFile.getAbsolutePath()));
                        }

                        int suffixIndex = webLogFile.getName().lastIndexOf(".");
                        String logFileNamePrefix = webLogFile.getName().substring(0, suffixIndex);
                        long logSize = FileTools.size(webLogFile);
                        if (!webLogFile.exists()) {
                            FileTools.write(webLogFile, header, false);
                        }
                        if (logSize < maxFileSize) {
                            FileTools.write(webLogFile, msg, true);
                        } else {
                            int logIndex = getLogFileIndex(config, webLogFolder, logFileNamePrefix, "csv");
                            if (logIndex == 0) {
                                FileTools.write(webLogFile, String.format("%s%s", header, msg), false);
                            } else {
                                backupLog(webLogFolder, webLogFile, logFileNamePrefix, logIndex, header, msg);
                            }
                        }
                        archiveLog(archiveDays, webLogFolder);
                    } catch (Exception e) {
                        LoggerFactory.getLogger(ServerLog.class).warn(e.getMessage(), e);
                    }
                }
            });
        }

        String hostname = CommonTools.getHostname();
        java.util.Date currentTime = new java.util.Date();
        String dateTimeStr = dtf.format(currentTime);

        String msg = String.format("%s,%s,%s,%s,%s,%s,%s,%s,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"",CommonTools.generateId(16),Thread.currentThread().getName(),Thread.currentThread().getId(),dateTimeStr, hostname, domain,
                port, uri, type, seq,
                handleParams(paramMaxLength, qs), handleParams(paramMaxLength,headers),
                handleParams(paramMaxLength, params),remoteAddr);

        if (checkUriAvailable(config, msg)) {
            webLogCacheArray.add(msg + System.lineSeparator());
        }
        return true;
    }

    private static boolean checkUriAvailable(ConfigProperties configProperties, String uri) {
        List<String> allowed = configProperties.getArray("Allowed");
        List<String> ignored = configProperties.getArray("Ignored");
        return checkUriAvailable(uri, allowed, ignored);
    }

    private static boolean checkUriAvailable(String msg, List<String> allowed, List<String> ignored) {
        boolean matchedAllowed = true;
        boolean matchedIgnored = false;
        if (allowed != null) {
            for (String regex : allowed) {
                if (!CommonTools.isBlank(regex)) {
                    matchedAllowed = msg.matches(regex);

                    if (matchedAllowed)
                        break;
                }
            }
        }
        if (matchedAllowed && ignored != null) {
            for (String regex : ignored) {
                if (!CommonTools.isBlank(regex)) {
                    matchedIgnored = msg.matches(regex);

                    if (matchedIgnored)
                        break;
                }
            }
        }

        boolean b = matchedAllowed && !matchedIgnored;
        if (!b) {
            LoggerFactory.getLogger(ServerLog.class).debug("Not available - '{}'", msg);
        }
        return b;
    }

    private static void backupLog(File logFolder, File logFile, String logFileNamePrefix, int logIndex, String header,
            String msg) throws Exception {
        String backupLogFileName = String.format("%s/%s.%s.csv", logFolder.getAbsolutePath(), logFileNamePrefix,
                logIndex);
        Path source = Paths.get(logFile.getAbsolutePath());
        Path target = Paths.get(backupLogFileName);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        FileTools.write(logFile, String.format("%s%s", header, msg), false);
    }

    private static int getLogFileIndex(ConfigProperties configProperties, File folder, String prefix, String suffix) {
        Integer maxBackupIndex = configProperties.getInteger("MaxBackupIndex", 10);
        for (int i = 1; i <= maxBackupIndex; i++) {
            String logFileName = String.format("%s/%s.%s.%s", folder.getAbsolutePath(), prefix, i, suffix);
            File logFile = new File(logFileName);
            if (!logFile.exists())
                return i;
        }
        return 0;
    }

    private static void archiveLog(int archiveDays, File sqlLogFolder) {
        if (archiveDays > 0) {
            try {
                long archiveDaysMs = new Date().getTime() - (archiveDays * 24 * 3600000L);
                deleteFilesOlderThan(sqlLogFolder, archiveDaysMs);
            } catch (Exception e) {
                LoggerFactory.getLogger(ServerLog.class).warn(e);
            }
        }
    }

    private static String handleParams(int paramMaxLength, Object params) {

        if (params == null)
            return "";

        StringBuilder paramSbf = new StringBuilder();
        if (params instanceof Map) {
            Map mapParams = (Map) params;
            List listParams = new ArrayList(mapParams.keySet());
            int size = listParams.size();
            for (int i = 0; i < size; i++) {
                Object key = listParams.get(i);
                Object val = mapParams.get(key);
                String str = null;
                if (val instanceof String[]) {
                    String[] strArr = (String[]) val;
                    str = Arrays.asList(strArr) + "";
                } else {
                    str = val + "";
                }
                paramSbf.append(String.format("%s=", key));
                if (str.length() > paramMaxLength) {
                    paramSbf.append(str.substring(0, paramMaxLength) + "...");
                } else {
                    paramSbf.append(str);
                }
                if (i < size - 1) {
                    paramSbf.append(";");
                }
            }
        } else {
            paramSbf.append(params);
        }
        return replaceToSigleLine(paramSbf.toString());
    }

    private static String replaceToSigleLine(String msg) {
        return CodeEscape.escapeToSingleLineForCsv(msg);
    }

    private static void deleteFilesOlderThan(File directory, long archiveDaysMs) throws IOException {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        boolean isLogFile = file.getName().toLowerCase().endsWith(".csv");
                        if (isLogFile) {
                            boolean canWrite = file.canWrite();
                            if (canWrite) {
                                long lastModified = file.lastModified();
                                if (lastModified < archiveDaysMs) {
                                    Files.deleteIfExists(Paths.get(file.toURI()));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static ConfigProperties getParams() {
        return config;
    }

    public static Map<String, String> getHeaders(HttpServletRequest request) {
        Map<String, String> headers = new IndexMap<String, String>();
        // headers.put("RequestRemoteAddr", request.getRemoteAddr());
        final Enumeration<String> ens = request.getHeaderNames();
        String en = null;
        if (ens != null) {
            while ((en = ens.nextElement()) != null) {
                headers.put(en, request.getHeader(en));
            }
        }
        return headers;
    }    
}
