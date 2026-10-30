#!/bin/bash

# H2 TCP port: read from the JsPlus config (env JsPlus_H2_PORT, default 3307).
export H2_PORT="${JsPlus_H2_PORT:-3307}"
set -e

# ------------------------------------------------------------------
# JsPlus-Tomcat automated test suite — TestAll.sh
# Flow:
#   1) Database init + H2 server (port $H2_PORT) — skipped when H2 is
#      already running (the DB file is locked by the running server,
#      so embedded fallback.sql/change.sql cannot apply).
#   2) Wait for H2 :$H2_PORT to LISTEN.
#   3) Run TestAll.js against the running server.
#      TestAll.js bootstraps the embedded Tomcat in the SAME JVM
#      (mirror of script/StartTomcat.js) so the global `tomcat` object
#      and TomcatScriptConsole.MasterConsole exist — that is the only
#      mode in which the suites can run.
#      Stdin is redirected to /dev/null so com.killcoding.jsplus.Main
#      exits right after the -Task completes (otherwise it blocks on
#      the interactive scanner, see Main.java scanner loop).
# ------------------------------------------------------------------

if [ -z "$1" ]; then
    SCRIPT_ENGINE=graalvm.js
else
    SCRIPT_ENGINE="$1"
fi

BIN_PATH="$(cd "$(dirname "$0")" && pwd)/"
JS_PLUS_PATH_ORIGIN="${BIN_PATH}../JsPlus/"
export JS_PLUS_PATH="${JS_PLUS_PATH_ORIGIN//\\//}"
LIB_HOME="${JS_PLUS_PATH}../LibHome/"

DEPEND_LIBS="$(find "${LIB_HOME}" -type f -name '*.jar' | tr '\n' ':')"
DEPEND_LIBS="${DEPEND_LIBS%:}"
if [ -z "$DEPEND_LIBS" ]; then
    echo "Error: No JARs found under LibHome"
    exit 1
fi

# The JDK directory name under Java/ comes from per-script env var
# JsPlus_JavaDir_<AppName> (default: default).
AppName="$(basename "$(cd "${JS_PLUS_PATH}/.." && pwd)")"
JavaDirEnvName="JsPlus_JavaDir_${AppName}"
JAVA_DIR="$(printenv "$JavaDirEnvName" || true)"
JAVA_DIR="${JAVA_DIR:-default}"
JAVA_BIN="${JS_PLUS_PATH}../Java/${JAVA_DIR}/bin/java"

if [ ! -f "$JAVA_BIN" ]; then
    echo "WARN: Bundled Java not found at $JAVA_BIN, falling back to system Java..."
    if [ -n "$JAVA_HOME" ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
    else
        JAVA_BIN="java"
    fi
fi

JvmParamsName="JsPlus_JvmParams_${AppName}"
JvmParams="$(printenv "$JvmParamsName" || true)"

echo "$JvmParamsName"

if [ -z "$JvmParams" ]; then
    JvmParams="-Xms256m -Xmx1024m -Xss256k"
    echo "WARN: Missing ${JvmParamsName} then using default params"
else
    echo "Using System Env JVM Params"
fi

echo "Using \"$JvmParams\""

# ------------------------------------------------------------------
# Step 0: Stop any existing services (H2 / Tomcat / consoles) so the
# database file is unlocked and ports $H2_PORT/8080 are free.
# ------------------------------------------------------------------
echo "[TestAll] Stopping existing services..."
"${JS_PLUS_PATH}bin/StopAll.sh"

# ------------------------------------------------------------------
# Step 1: Database init + H2 server (port $H2_PORT)
# Skipped when H2 is already running: the DB file is locked by the
# running server, so embedded fallback.sql/change.sql cannot apply.
# ------------------------------------------------------------------
if (netstat -tln 2>/dev/null | grep -q ":$H2_PORT .*LISTEN") || (ss -tln 2>/dev/null | grep -q ":$H2_PORT .*LISTEN"); then
    echo "[TestAll] H2 already running on :$H2_PORT - skipping DB init and H2 start."
else
    echo "[TestAll] Initializing database (fallback.sql -> change.sql)..."
    "${JS_PLUS_PATH}bin/StartH2Console.sh" init tomcatdb
    echo "[TestAll] Starting H2 server (port $H2_PORT)..."
    "${JS_PLUS_PATH}bin/BG_StartH2.sh" "$SCRIPT_ENGINE"
fi

echo "[TestAll] Waiting for H2 :$H2_PORT..."
H2_READY=0
for i in $(seq 1 45); do
    if (netstat -tln 2>/dev/null | grep -q ":$H2_PORT .*LISTEN") || (ss -tln 2>/dev/null | grep -q ":$H2_PORT .*LISTEN"); then
        H2_READY=1
        break
    fi
    sleep 1
done
if [ "$H2_READY" != "1" ]; then
    echo "[TestAll] ERROR: H2 did not start on port $H2_PORT."
    exit 1
fi
echo "[TestAll] H2 ready."

# ------------------------------------------------------------------
# Step 2: Run TestAll.js against the running server.
# TestAll.js bootstraps the embedded Tomcat in this JVM (mirror of
# script/StartTomcat.js), waits for :8080, runs the 4 suites and stops
# the server before finishing.
# "< /dev/null" closes stdin: the JsPlus Main scanner gets EOF right
# after the -Task finishes and the JVM exits cleanly (Main.java).
# TESTALL_TASK tells TestAll.js it is running as the CI/batch task (not an
# interactive console eval), so it hard-halts its own JVM (Runtime.halt(0))
# after the suites — otherwise the non-daemon TomcatServer await thread
# would keep this JVM alive forever and Step 3 would never start.
# ------------------------------------------------------------------
export TESTALL_TASK=1
"$JAVA_BIN" $JvmParams -cp "$DEPEND_LIBS" com.killcoding.jsplus.Main -ScriptEngine "$SCRIPT_ENGINE" -Env script -WriteHistory false -Task "${JS_PLUS_PATH}../test/TestAll.js" < /dev/null
RC=$?
echo "TestAll exit code: $RC"

# TestAll.js now exits via System.exit(0) (NOT ScriptConsole.exit()), so the
# shared exit.tmp signal file is never written and the H2 ghost started by
# BG_StartH2.sh keeps running. Stop ALL remaining services (H2) on every exit
# path so TestAll leaves nothing behind.
cleanup() {
    echo "[TestAll] Stopping all remaining services..."
    "${JS_PLUS_PATH}bin/StopAll.sh" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ------------------------------------------------------------------
# Step 3: Simple load test — TestAll.js stops shared services when it
# finishes, so make sure a WORKING H2 is reachable on :$H2_PORT first
# (netstat may still show a half-dead H2 holding the port, so we do a
# real TCP connect test); then start a standalone Tomcat, run LoadTest
# against it, and stop Tomcat again.
# Outputs: ../test_reports/loadtest-result.json + loadtest-dashboard.html
# Override via env: LOADTEST_URL, LOADTEST_CONCURRENCY,
#                    LOADTEST_TOTAL, LOADTEST_TIMEOUT
# ------------------------------------------------------------------
if (exec 3<>/dev/tcp/127.0.0.1/$H2_PORT) 2>/dev/null; then
    echo "[TestAll] H2 reachable on :$H2_PORT."
else
    echo "[TestAll] H2 not reachable on :$H2_PORT, restarting H2..."
    "${JS_PLUS_PATH}bin/BG_StartH2.sh" "$SCRIPT_ENGINE"
    H2_UP=0
    for i in $(seq 1 45); do
        if (exec 3<>/dev/tcp/127.0.0.1/$H2_PORT) 2>/dev/null; then
            H2_UP=1
            break
        fi
        sleep 1
    done
    if [ "$H2_UP" != "1" ]; then
        echo "[TestAll] WARN: H2 did not start; skipping load test."
        exit 0
    fi
    echo "[TestAll] H2 restarted."
fi

echo "[TestAll] Starting Tomcat for load test..."
"${JS_PLUS_PATH}bin/BG_StartTomcat.sh" "$SCRIPT_ENGINE"

echo "[TestAll] Waiting for Tomcat :8080/:8443..."
WEB_READY=0
for i in $(seq 1 60); do
    if (netstat -tln 2>/dev/null | grep -q ":8080 .*LISTEN") || (ss -tln 2>/dev/null | grep -q ":8080 .*LISTEN"); then
        WEB_READY=1
        break
    fi
    sleep 1
done
if [ "$WEB_READY" != "1" ]; then
    echo "[TestAll] WARN: Tomcat did not start; skipping load test."
    exit 0
fi
echo "[TestAll] Tomcat ready. Running load test..."

# LoadTest.js — pure-JS load test through the JsPlus console (replaces the
# old compiled LoadTest.java step). Env vars LOADTEST_* are read by the
# script itself via System.getenv(), so they MUST be exported (plain shell
# variables would not reach the child java process). Stdin /dev/null lets
# Main exit right after the task.
export LOADTEST_URL="${LOADTEST_URL:-https://localhost:8443/default/}"
export LOADTEST_CONCURRENCY="${LOADTEST_CONCURRENCY:-10}"
export LOADTEST_TOTAL="${LOADTEST_TOTAL:-10000}"
export LOADTEST_TIMEOUT="${LOADTEST_TIMEOUT:-60}"
( cd "${JS_PLUS_PATH}../test" && "$JAVA_BIN" ${JvmParams} -cp "$DEPEND_LIBS" \
    com.killcoding.jsplus.Main -ScriptEngine "$SCRIPT_ENGINE" -Env script -WriteHistory false \
    -Task "${JS_PLUS_PATH}../test/LoadTest.js" < /dev/null )
LT_RC=$?

echo "[TestAll] Stopping Tomcat after load test..."
"${JS_PLUS_PATH}bin/StopTomcat.sh"

RC="${RC:-1}"

# ------------------------------------------------------------------
# Step 4b: Archive the test reports under the same docs layout as the
# javadoc site (JsPlus-Core standard): docs/reports/<version>.jar (one
# jar per run) plus docs/latest/report/ as the browsable newest reports.
# Version is taken from the jsplus-tomcat jar name. A failure to archive
# is only a WARN and never changes the suite exit code (RC captured above).
# ------------------------------------------------------------------
PROJECT_ROOT="${JS_PLUS_PATH}.."
TOMCAT_JAR=$(ls "$PROJECT_ROOT/LibHome/jsplus/default/"jsplus-tomcat-*.jar 2>/dev/null | head -n 1)
if [ -z "$TOMCAT_JAR" ]; then
    echo "WARN: jsplus-tomcat-*.jar not found, skipping report archive."
else
    TOMCAT_VERSION=$(basename "$TOMCAT_JAR" | sed 's/^jsplus-tomcat-//; s/\.jar$//')
    echo "[TestAll] Archiving test reports as docs/reports/${TOMCAT_VERSION}.jar (docs/latest/report)..."
    mkdir -p "$PROJECT_ROOT/docs/reports" "$PROJECT_ROOT/docs/latest/report"
    rm -f "$PROJECT_ROOT/docs/reports/${TOMCAT_VERSION}.jar"
    if jar cf "$PROJECT_ROOT/docs/reports/${TOMCAT_VERSION}.jar" -C "$PROJECT_ROOT/docs/latest/report" .; then
        echo "[TestAll] Packed docs/reports/${TOMCAT_VERSION}.jar"
    else
        echo "WARN: Failed to pack test report jar, continuing."
    fi
fi

echo "TestAll exit code: $RC"
exit "$RC"
