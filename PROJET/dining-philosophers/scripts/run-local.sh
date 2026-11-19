#!/usr/bin/env bash
# Lance tout en local, sans Docker.
# Usage : ./scripts/run-local.sh [MODE] [PAIR_MODE] [N] [RACE_WINDOW]
#   ./scripts/run-local.sh SPOILED
#   ./scripts/run-local.sh NAIVE_SUPERVISOR
#   ./scripts/run-local.sh PAIR_SUPERVISOR UNSAFE 5 20ms
#   ./scripts/run-local.sh PAIR_SUPERVISOR LOCKED
#   ./scripts/run-local.sh TOKEN                      # partie 3.3 (PAIR_MODE ignoré)
#   DROP_RATE=0.2 MAX_DELAY=50ms ./scripts/run-local.sh TOKEN   # 3.3 avec pannes réseau simulées
#   ASSIGNMENT=NEIGHBOURS ./scripts/run-local.sh TOKEN          # 3.3 avec la table ronde classique
#   EVENTS=KAFKA ./scripts/run-local.sh TOKEN        # 6 : événements par Kafka (Kafka sur localhost:9092)
#   ./scripts/run-local.sh ZOOKEEPER                  # 3.4 (ZooKeeper doit tourner sur localhost:2181,
#                                                     #      ex : docker run -d -p 2181:2181 zookeeper:3.9)
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

PEERS=""
for ((i = 0; i < N; i++)); do PEERS+="${PEERS:+,}http://localhost:$((8090 + i))"; done
EVENTS="${EVENTS:-HTTP}"
KAFKA_ON=false
[ "$EVENTS" = "KAFKA" ] && KAFKA_ON=true

echo "› démarrage (mode=$MODE, pair-mode=$PAIR_MODE, N=$N, race-window=$RACE_WINDOW)"
start monitor monitor-service/target/monitor-service.jar \
  --monitor.philosophers="$N" --monitor.detect-neighbour-conflicts="$DETECT_CONFLICTS" \
  --monitor.philosopher-urls="$PEERS" --monitor.kafka.enabled="$KAFKA_ON"
start supervisor supervisor-service/target/supervisor-service.jar \
  --supervisor.forks="$N" --supervisor.pair-mode="$PAIR_MODE" --supervisor.race-window="$RACE_WINDOW"
sleep 6
for ((i = 0; i < N; i++)); do
  start "philo-$i" philosopher-service/target/philosopher-service.jar \
    --server.port=$((8090 + i)) --philo.id="$i" --philo.count="$N" --philo.mode="$MODE" \
    --philo.peers="$PEERS" --philo.assignment="${ASSIGNMENT:-RANDOM}" --philo.zookeeper.connect="${ZOOKEEPER:-localhost:2181}" \
    --philo.token.drop-rate="${DROP_RATE:-0}" --philo.token.max-delay="${MAX_DELAY:-0ms}" \
    --philo.events="$EVENTS"
done

echo
echo "Tableau de bord : http://localhost:8080"
echo "Logs            : $ROOT/logs/"
echo "Arrêt           : ./scripts/stop-local.sh"
