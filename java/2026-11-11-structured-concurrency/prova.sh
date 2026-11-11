#!/bin/sh
set -eu

if [ -z "${JAVA28:-}" ]; then
    echo "Impostate JAVA28 con il percorso del comando java del JDK 28 (vedi README)"
    exit 1
fi

if ! "$JAVA28" -version 2>&1 | grep -q 'version "28'; then
    echo "JAVA28 non punta a un JDK 28:"
    "$JAVA28" -version
    exit 1
fi

VARIANTI="executor executor-antifrode-prima executor-per-richiesta scope scope-interruzione-ignorata"

cd "$(dirname "$0")"
mkdir -p prove

for variante in $VARIANTI; do
    echo "== $variante"
    "$JAVA28" bonifico/Prova.java "$variante" | tee "prove/$variante.txt"
    if ! grep -q '^1.000 bonifici' "prove/$variante.txt"; then
        echo "La variante $variante non è arrivata in fondo: guardate prove/$variante.txt"
        exit 1
    fi
done
