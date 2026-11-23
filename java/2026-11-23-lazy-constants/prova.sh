#!/bin/sh
set -eu

VARIANTI="final if-null double-checked holder lazy-constant lazy-constant-retry"
SCENARI="avvio concorrenza db-giu db-giu-concorrenza db-giu-a-lungo"
CLASSI=target/classes

if [ -z "${JAVA28:-}" ]; then
    echo "Impostate JAVA28 con il percorso del comando java del JDK 28 (vedi README)"
    exit 1
fi

if ! "$JAVA28" -version 2>&1 | grep -q 'version "28'; then
    echo "JAVA28 non punta a un JDK 28:"
    "$JAVA28" -version
    exit 1
fi

cd "$(dirname "$0")"

rm -rf "$CLASSI"
"$(dirname "$JAVA28")/javac" --release 28 --enable-preview -Xlint:-preview \
    -d "$CLASSI" bonifico/*.java

mkdir -p prove

for variante in $VARIANTI; do
    for scenario in $SCENARI; do
        output="prove/$variante-$scenario.txt"
        echo "== $variante, $scenario"
        "$JAVA28" --enable-preview -cp "$CLASSI" bonifico.Prova "$variante" "$scenario" |
            tee "$output"
        if ! grep -q 'caricamenti dal DB' "$output"; then
            echo "$variante, $scenario: la prova non è arrivata in fondo, guardate $output"
            exit 1
        fi
    done
done
