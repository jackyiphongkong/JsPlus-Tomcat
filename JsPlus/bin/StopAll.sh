#!/bin/bash
set -e

# Get the parent directory of the script location (application root)
ScriptDir="$(cd "$(dirname "$0")" && pwd)"
AppRootDir="$(dirname "$ScriptDir")"

# Convert to forward slash (already in Linux)
Pattern="$AppRootDir"

echo "=============================================="
echo "Application Root: $AppRootDir"
echo "Pattern: $Pattern"
echo "=============================================="
echo "Searching and terminating Java processes..."

# Find Java processes (java or javaw) whose command line contains the pattern
pids=$(ps -eo pid,args | grep -E 'java|javaw' | grep -F "$Pattern" | grep -v grep | awk '{print $1}')

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
echo "Operation completed."