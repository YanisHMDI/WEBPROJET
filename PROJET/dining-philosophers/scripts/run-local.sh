#!/usr/bin/env bash
# Lance tout en local, sans Docker.
# Usage : ./scripts/run-local.sh [MODE] [PAIR_MODE] [N] [RACE_WINDOW]
#   ./scripts/run-local.sh SPOILED
#   ./scripts/run-local.sh NAIVE_SUPERVISOR
#   ./scripts/run-local.sh PAIR_SUPERVISOR UNSAFE 5 20ms
#   ./scripts/run-local.sh PAIR_SUPERVISOR LOCKED
set -euo pipefail

MODE="${1:-SPOILED}"
PAIR_MODE="${2:-LOCKED}"
N="${3:-5}"
RACE_WINDOW="${4:-0ms}"
DETECT_CONFLICTS=true
[ "$MODE" = "SPOILED" ] && DETECT_CONFLICTS=false

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p logs
: > .pids

echo "› build"
mvn -B -q package -DskipTests

start() {
  local name="$1"; shift
  java -jar "$@" > "logs/$name.log" 2>&1 &
  echo $! >> .pids
  echo "  $name (pid $!)"
}

echo "› démarrage (mode=$MODE, pair-mode=$PAIR_MODE, N=$N, race-window=$RACE_WINDOW)"
start monitor monitor-service/target/monitor-service.jar \
  --monitor.philosophers="$N" --monitor.detect-neighbour-conflicts="$DETECT_CONFLICTS"
start supervisor supervisor-service/target/supervisor-service.jar \
  --supervisor.forks="$N" --supervisor.pair-mode="$PAIR_MODE" --supervisor.race-window="$RACE_WINDOW"
sleep 6
for ((i = 0; i < N; i++)); do
  start "philo-$i" philosopher-service/target/philosopher-service.jar \
    --server.port=$((8090 + i)) --philo.id="$i" --philo.count="$N" --philo.mode="$MODE"
done

echo
echo "Tableau de bord : http://localhost:8080"
echo "Logs            : $ROOT/logs/"
echo "Arrêt           : ./scripts/stop-local.sh"
