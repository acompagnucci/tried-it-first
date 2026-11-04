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

CONTO_IN_CACHE=100
CONTO_FUORI_CACHE=500
ESECUZIONI=5

cd "$(dirname "$0")"
mkdir -p prove

senza_e_con_preview() (
    programma="$1"
    nome="$2"
    "$JAVA28" "$programma" > "prove/$nome.txt" 2>&1
    "$JAVA28" --enable-preview "$programma" > "prove/$nome-preview.txt" 2>&1
)

ripeti() (
    esecuzione=1
    while [ "$esecuzione" -le "$ESECUZIONI" ]; do
        "$@"
        esecuzione=$((esecuzione + 1))
    done
)

senza_e_con_preview confronti/Confronti.java confronti
senza_e_con_preview rate/Rate.java rate

{
    "$JAVA28" accrediti/Accrediti.java "$CONTO_IN_CACHE" 2>&1
    ripeti "$JAVA28" accrediti/Accrediti.java "$CONTO_FUORI_CACHE" 2>/dev/null
} > prove/accrediti.txt

if "$JAVA28" --enable-preview accrediti/Accrediti.java "$CONTO_FUORI_CACHE" > prove/accrediti-preview.txt 2>&1; then
    echo "Con --enable-preview accrediti/Accrediti.java doveva non compilare"
    exit 1
fi

ripeti "$JAVA28" accrediti/AccreditiCorretti.java "$CONTO_FUORI_CACHE" > prove/accrediti-corretti.txt 2>&1
ripeti "$JAVA28" --enable-preview accrediti/AccreditiCorretti.java "$CONTO_FUORI_CACHE" > prove/accrediti-corretti-preview.txt 2>&1

for f in prove/confronti.txt prove/confronti-preview.txt; do
    echo "== $f"
    grep -E '^(pratica|scadenza|IdentityHashMap|WeakHashMap)' "$f"
done
for f in prove/rate.txt prove/rate-preview.txt; do
    echo "== $f"
    grep -E '^Total' "$f"
done
echo "== prove/accrediti.txt"
grep '^conto' prove/accrediti.txt
echo "== prove/accrediti-preview.txt"
grep -E 'error|required|found' prove/accrediti-preview.txt
for f in prove/accrediti-corretti.txt prove/accrediti-corretti-preview.txt; do
    echo "== $f"
    grep '^conto' "$f"
done
