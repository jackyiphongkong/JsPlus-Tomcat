#!/bin/bash
set -e

ScriptDir="$(cd "$(dirname "$0")" && pwd)"

exec "${ScriptDir}/Stop.sh" StartTomcat.sh
