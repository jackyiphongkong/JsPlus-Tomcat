#!/bin/bash
set -e

# ------------------------------------------------------------------
# JsPlus-Tomcat H2 database console / initializer
# Usage:
#   StartH2Console.sh                 Interactive shell (default DB: appdb)
#   StartH2Console.sh <DB_NAME>       Interactive shell on the given DB
#   StartH2Console.sh init [DB_NAME]  Reset + rebuild schema:
#                                     fallback.sql -> change.sql
#                                     (default DB for init: tomcatdb,
#                                      matching DataSource.properties)
# ------------------------------------------------------------------

INIT_MODE=0
if [ "$1" = "init" ]; then
    INIT_MODE=1
    if [ -z "$2" ]; then
        DB_NAME="tomcatdb"
    else
        DB_NAME="$2"
    fi
elif [ -z "$1" ]; then
    DB_NAME="appdb"
else
    DB_NAME="$1"
fi

BIN_PATH="$(cd "$(dirname "$0")" && pwd)/"
JS_PLUS_PATH_ORIGIN="${BIN_PATH}../"
JS_PLUS_PATH="${JS_PLUS_PATH_ORIGIN//\\//}"
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
    JvmParams="-Xms128m -Xmx128m -Xss256k"
    echo "WARN: Missing ${JvmParamsName} then using default params"
else
    echo "Using System Env JVM Params"
fi

echo "Using \"$JvmParams\""

if [ "$INIT_MODE" = "1" ]; then
    echo "[StartH2Console] Initializing database \"$DB_NAME\"..."
    echo "[StartH2Console] Executing dbscript/fallback.sql ..."
    "$JAVA_BIN" $JvmParams -cp "$DEPEND_LIBS" org.h2.tools.RunScript \
        -url "jdbc:h2:${JS_PLUS_PATH}../Database/${DB_NAME}" \
        -user sa -script "${JS_PLUS_PATH}../dbscript/fallback.sql" -checkResults
    echo "[StartH2Console] Executing dbscript/change.sql ..."
    "$JAVA_BIN" $JvmParams -cp "$DEPEND_LIBS" org.h2.tools.RunScript \
        -url "jdbc:h2:${JS_PLUS_PATH}../Database/${DB_NAME}" \
        -user sa -script "${JS_PLUS_PATH}../dbscript/change.sql" -checkResults
    echo "[StartH2Console] Database \"$DB_NAME\" initialized (9 tables + sample_user)."
else
    "$JAVA_BIN" $JvmParams -cp "$DEPEND_LIBS" org.h2.tools.Shell \
        -url "jdbc:h2:${JS_PLUS_PATH}../Database/${DB_NAME}" -user sa
fi
