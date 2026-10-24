#!/bin/bash
set -e

BIN_PATH="$(cd "$(dirname "$0")" && pwd)/"
JS_PLUS_PATH_ORIGIN="${BIN_PATH}../"
export JS_PLUS_PATH="${JS_PLUS_PATH_ORIGIN//\\//}"

nohup "${JS_PLUS_PATH}bin/StartH2.sh" "$@" > /dev/null 2>&1 &