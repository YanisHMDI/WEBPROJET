#!/usr/bin/env bash
# Partie 5 — mesure des performances du calcul Master–Worker.
# Prérequis : les philosophes tournent (./scripts/run-local.sh SPOILED). P0 (port 8090) joue le Master,
# les autres philosophes sont les Workers.
# Usage : ./scripts/bench-graph.sh [MASTER_URL]
set -euo pipefail

MASTER="${1:-http://localhost:8090}"
SIZES=("10000 50000" "100000 500000" "200000 2000000")   # nœuds arcs
WORKERS=(1 2 3 4)
REPEAT=3

run() {   # $1 = chemin, $2 = JSON → temps distribué (meilleur de REPEAT essais) ; temps séquentiel ; correct
  local best="" seq="" ok=""
  for _ in $(seq "$REPEAT"); do
    local out
    out=$(curl -s -X POST "$MASTER/api/master/$1" -H 'Content-Type: application/json' -d "$2")
    local d s c
    d=$(echo "$out" | python3 -c 'import json,sys; print(json.load(sys.stdin)["distributedMs"])')
    s=$(echo "$out" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sequentialMs"])')
    c=$(echo "$out" | python3 -c 'import json,sys; print(json.load(sys.stdin)["correct"])')
    if [ -z "$best" ] || [ "$d" -lt "$best" ]; then best=$d; fi
    seq=$s; ok=$c
  done
  echo "$best $seq $ok"
}

printf "%-10s %-10s %-8s %-15s %-15s %-8s\n" calcul nœuds workers "distribué (ms)" "séquentiel (ms)" correct
for size in "${SIZES[@]}"; do
  read -r n m <<< "$size"
  for w in "${WORKERS[@]}"; do
    read -r d s c <<< "$(run degrees "{\"nodes\":$n,\"edges\":$m,\"seed\":1,\"workers\":$w}")"
    printf "%-10s %-10s %-8s %-15s %-15s %-8s\n" degrés "$n/$m" "$w" "$d" "$s" "$c"
  done
  for w in "${WORKERS[@]}"; do
    read -r d s c <<< "$(run reachability "{\"nodes\":$n,\"edges\":$m,\"seed\":1,\"workers\":$w,\"source\":0,\"target\":$((n - 1))}")"
    printf "%-10s %-10s %-8s %-15s %-15s %-8s\n" accès "$n/$m" "$w" "$d" "$s" "$c"
  done
done
