#!/bin/bash
set -e

if [ -z "$1" ]; then
    SCRIPT_ENGINE=graalvm.js
else
    SCRIPT_ENGINE="$1"
fi

BIN_PATH="$(cd "$(dirname "$0")" && pwd)/"
JS_PLUS_PATH_ORIGIN="${BIN_PATH}../"
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
    JvmParams="-Xms64m -Xmx64m -Xss256k"
    echo "WARN: Missing ${JvmParamsName} then using default params"
else
    echo "Using System Env JVM Params"
fi

echo "Using \"$JvmParams\""

if ! command -v rlwrap &> /dev/null; then
    sudo apt-get install rlwrap -y
fi

rlwrap "$JAVA_BIN" $JvmParams -cp "$DEPEND_LIBS" com.killcoding.jsplus.Main -ScriptEngine "$SCRIPT_ENGINE" -Env ghost -WriteHistory false -GhostName H2Ghost
