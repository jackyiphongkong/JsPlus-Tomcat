#!/usr/bin/env bash
# ===========================================================================
# packJdk.sh - Build a trimmed custom JRE for JsPlus (jdeps + jlink)
#
# Usage:   packJdk.sh
#
# Prereq:  A JDK with jdeps/jlink must be available. The bundled GraalVM
#          (Java/default) is used by default; JAVA_HOME is the fallback.
# Output:  <script dir>/Java/runtime/  (jlink-trimmed runtime, previous output is replaced)
# ===========================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# 1. Base paths
# ---------------------------------------------------------------------------
BIN_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/"
LIB_HOME="${BIN_PATH}LibHome"
RUNTIME_PACK_OUTPUT="${BIN_PATH}Java/runtime"

# ---------------------------------------------------------------------------
# 2. Build classpath from every JAR under LibHome
# ---------------------------------------------------------------------------
DEPEND_LIBS=""
while IFS= read -r jar; do
    DEPEND_LIBS="${DEPEND_LIBS};${jar}"
done < <(find "${LIB_HOME}" -type f -name '*.jar')
DEPEND_LIBS="${DEPEND_LIBS:1}"

if [[ -z "${DEPEND_LIBS}" ]]; then
    echo "Error: No JAR files found under ${LIB_HOME}"
    exit 1
fi

# ---------------------------------------------------------------------------
# 3. Locate Java - bundled GraalVM preferred, JAVA_HOME as fallback
# ---------------------------------------------------------------------------
JAVA_HOME_ORIG="${JAVA_HOME:-}"
JAVA_HOME="${BIN_PATH}Java/default"
JAVA_BIN="${JAVA_HOME}/bin"

if [[ ! -x "${JAVA_BIN}/jdeps" ]]; then
    echo "WARN: Bundled Java not found at ${JAVA_HOME}, falling back to system JAVA_HOME..."
    if [[ -n "${JAVA_HOME_ORIG}" ]]; then
        JAVA_HOME="${JAVA_HOME_ORIG}"
        JAVA_BIN="${JAVA_HOME_ORIG}/bin"
    else
        echo "Error: Java JDK not found. Set JAVA_HOME to a JDK to use packJdk."
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# 4. Resolve module dependencies with jdeps
# ---------------------------------------------------------------------------
TMPFILE="$(mktemp /tmp/jdeps_modules_XXXXXX.txt)"

if ! "${JAVA_BIN}/jdeps" --multi-release 25 --ignore-missing-deps \
        -cp "${DEPEND_LIBS}" \
        --print-module-deps ${LIB_HOME}/jsplus/default/*.jar > "${TMPFILE}" 2>/dev/null; then
    echo "Error: jdeps failed. Check the classpath or JDK version."
    rm -f "${TMPFILE}"
    exit 1
fi

# Keep the last non-warning line (jdeps prints "Warning: split package ..."
# before the module list); the final line is the comma-separated module list.
modules="$(grep -v '^Warning:' "${TMPFILE}" | tail -n 1 || true)"
rm -f "${TMPFILE}"

if [[ -z "${modules}" ]]; then
    echo "Error: jdeps returned no module dependencies."
    exit 1
fi

echo "Depend modules: ${modules}"

# ---------------------------------------------------------------------------
# 5. Build the trimmed runtime with jlink
# ---------------------------------------------------------------------------
if [[ -d "${RUNTIME_PACK_OUTPUT}" ]]; then
    echo "Removing previous runtime at ${RUNTIME_PACK_OUTPUT}"
    rm -rf "${RUNTIME_PACK_OUTPUT}"
fi

if ! "${JAVA_BIN}/jlink" --module-path "${JAVA_HOME}/jmods" \
        --add-modules "${modules}" --output "${RUNTIME_PACK_OUTPUT}"; then
    echo "Error: jlink failed. See messages above."
    exit 1
fi

echo "Custom JRE created at ${RUNTIME_PACK_OUTPUT}"
