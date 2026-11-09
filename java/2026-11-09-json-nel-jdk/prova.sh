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

cd "$(dirname "$0")"

if [ ! -d lib/jackson2 ] || [ ! -d lib/jackson3 ]; then
    echo "Mancano i jar di Jackson: lanciate prima ./scarica-jackson.sh"
    exit 1
fi

mkdir -p prove

con_jackson() (
    versione="$1"
    modo="$2"
    "$JAVA28" --class-path "lib/jackson$versione/*" "jackson$versione/LeggiBonifico.java" "$modo" bonifici/*.json \
        > "prove/jackson$versione-$modo.txt" 2>&1
)

"$JAVA28" --add-modules jdk.incubator.json jdk28/LeggiBonifico.java bonifici/*.json > prove/jdk28.txt 2>&1
con_jackson 2 normale
con_jackson 2 severo
con_jackson 3 normale
con_jackson 3 severo

for f in prove/jdk28.txt prove/jackson2-normale.txt prove/jackson3-normale.txt prove/jackson2-severo.txt prove/jackson3-severo.txt; do
    echo "== $f"
    grep -v -e '^WARNING' -e '^ at \[Source' "$f"
done
