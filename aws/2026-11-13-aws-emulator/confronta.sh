#!/bin/sh
set -eu

cd "$(dirname "$0")"

LARGHEZZA=190

for file in deploy.txt api.txt attese.txt risultato.txt cli.txt; do
    echo "== $file (a sinistra l'emulatore, a destra AWS)"
    diff -y -W "$LARGHEZZA" "prove/emulatore/$file" "prove/aws/$file" || true
    echo
done
