#!/bin/bash
set -e

# ------------------------------------------------------------------
# JsPlus-Tomcat automated test suite — TestAll.sh
# Flow:
#   1) Database init + H2 server (port 3306) — skipped when H2 is
#      already running (the DB file is locked by the running server,
#      so embedded fallback.sql/change.sql cannot apply).
#   2) Wait for H2 :3306 to LISTEN.
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
# database file is unlocked and ports 3306/8080 are free.
# ------------------------------------------------------------------
echo "[TestAll] Stopping existing services..."
"${JS_PLUS_PATH}bin/StopAll.sh"

# ------------------------------------------------------------------
# Step 1: Database init + H2 server (port 3306)
# Skipped when H2 is already running: the DB file is locked by the
# running server, so embedded fallback.sql/change.sql cannot apply.
# ------------------------------------------------------------------
if (netstat -tln 2>/dev/null | grep -q ":3306 .*LISTEN") || (ss -tln 2>/dev/null | grep -q ":3306 .*LISTEN"); then
    echo "[TestAll] H2 already running on :3306 - skipping DB init and H2 start."
else
    echo "[TestAll] Initializing database (fallback.sql -> change.sql)..."
    "${JS_PLUS_PATH}bin/StartH2Console.sh" init tomcatdb
    echo "[TestAll] Starting H2 server (port 3306)..."
    "${JS_PLUS_PATH}bin/BG_StartH2.sh" "$SCRIPT_ENGINE"
fi

echo "[TestAll] Waiting for H2 :3306..."
H2_READY=0
for i in $(seq 1 45); do
    if (netstat -tln 2>/dev/null | grep -q ":3306 .*LISTEN") || (ss -tln 2>/dev/null | grep -q ":3306 .*LISTEN"); then
        H2_READY=1
        break
    fi
    sleep 1
done
if [ "$H2_READY" != "1" ]; then
    echo "[TestAll] ERROR: H2 did not start on port 3306."
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
# ------------------------------------------------------------------
"$JAVA_BIN" $JvmParams -cp "$DEPEND_LIBS" com.killcoding.jsplus.Main -ScriptEngine "$SCRIPT_ENGINE" -Env script -WriteHistory false -Task "${JS_PLUS_PATH}../test/TestAll.js" < /dev/null
echo "TestAll exit code: $?"
exit $?
