#!/usr/bin/env bash
cd "$(dirname "$0")/.."
[ -f .pids ] || { echo "rien à arrêter"; exit 0; }
while read -r pid; do
  kill "$pid" 2>/dev/null && echo "arrêt $pid"
done < .pids
rm -f .pids
