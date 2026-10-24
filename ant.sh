#!/bin/bash
set -e

BIN_PATH_ORIGIN="$(cd "$(dirname "$0")" && pwd)/"
BIN_PATH="${BIN_PATH_ORIGIN//\\//}"

JAVA_HOME_ORIG="${JAVA_HOME:-}"
ANT_HOME_ORIG="${ANT_HOME:-}"

export JAVA_HOME="${BIN_PATH}Java/default/"
export ANT_HOME="${BIN_PATH}ant/default/"

if [ ! -d "$JAVA_HOME" ]; then
    echo "WARN: Bundled Java not found at $JAVA_HOME, falling back to system JAVA_HOME..."
    if [ -n "$JAVA_HOME_ORIG" ]; then
        export JAVA_HOME="$JAVA_HOME_ORIG"
    else
        unset JAVA_HOME
    fi
fi

if [ ! -d "$ANT_HOME" ]; then
    echo "WARN: Bundled Ant not found at $ANT_HOME, falling back to system ANT_HOME..."
    if [ -n "$ANT_HOME_ORIG" ]; then
        export ANT_HOME="$ANT_HOME_ORIG"
    else
        unset ANT_HOME
    fi
fi

if [ -n "$ANT_HOME" ]; then
    "${ANT_HOME}bin/ant"
else
    ant
fi
