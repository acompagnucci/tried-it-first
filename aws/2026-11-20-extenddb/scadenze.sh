#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "${1:-}"

MINUTI_MASSIMI=${2:-180}
SECONDI_TRA_I_CONTROLLI=60

rimasti() (
    aws dynamodb scan --table-name "$MOVIMENTI" --select COUNT --query Count --output text
)

{
    banca "$DOVE" CaricaScadenze "$MOVIMENTI"
    inizio=$(date +%s)
    for minuto in $(seq 0 "$MINUTI_MASSIMI"); do
        quanti=$(rimasti)
        echo "$(date -u +%H:%M:%S) minuto $minuto: $quanti movimenti ancora in tabella"
        [ "$quanti" -gt 0 ] || break
        attesa=$((inizio + (minuto + 1) * SECONDI_TRA_I_CONTROLLI - $(date +%s)))
        [ "$attesa" -le 0 ] || sleep "$attesa"
    done
} > "$PROVE/06-scadenze.txt"
tail -n 3 "$PROVE/06-scadenze.txt"
