#!/bin/bash
set -e

ScriptDir="$(cd "$(dirname "$0")" && pwd)"

# Project root: only a command line holding a path under it is eligible. A
# launcher in another project that happens to share the script name (StartH2.sh)
# or carries a jsplus-tomcat-*.jar on its classpath is therefore skipped.
AppRootDir="$(cd "${ScriptDir}/../.." && pwd)"

if [ -z "$1" ]; then
    echo "Usage: $0 <StartXxx.sh>"
    echo "  e.g. $0 StartH2.sh"
    exit 1
fi

Arg="$1"
case "$Arg" in
    /*) TargetSh="$Arg" ;;
    *)  TargetSh="${ScriptDir}/${Arg}" ;;
esac
if [ -d "$(dirname "$TargetSh")" ]; then
    TargetSh="$(cd "$(dirname "$TargetSh")" && pwd)/$(basename "$TargetSh")"
fi

# Derive the keyword from the file name: StartH2.sh -> StartH2
Base="$(basename "$1")"
Base="${Base%.*}"

if [ -z "$Base" ]; then
    echo "[ERROR] Cannot derive keyword from \"$1\"."
    exit 1
fi

echo "=============================================="
echo "TargetSh    : $TargetSh"
echo "Keyword     : $Base"
echo "ProjectRoot : $AppRootDir"
echo "=============================================="
echo "Searching and terminating Java processes..."
echo "(only processes whose command line holds a path under ProjectRoot are eligible)"

pids=$(ps -eo pid,args | grep -E 'java|javaw' | grep -F "${AppRootDir}/" | grep -i -F "$Base" | grep -v grep | awk '{print $1}')

if [ -n "$pids" ]; then
    echo "$pids" | while read pid; do
        echo "Terminating PID: $pid"
        kill -9 "$pid" 2>/dev/null || true
    done
    count=$(echo "$pids" | wc -l)
    echo "Terminated $count process(es)."
else
    echo "No matching Java processes found."
fi

echo