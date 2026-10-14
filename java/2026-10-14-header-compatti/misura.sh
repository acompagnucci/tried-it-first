#!/bin/sh
set -eu

if [ -z "${JAVA21:-}" ] || [ -z "${JAVA27:-}" ]; then
    echo "Impostate JAVA21 e JAVA27 con il percorso del comando java di ciascun JDK (vedi README)"
    exit 1
fi

cd "$(dirname "$0")"
mkdir -p prove

"$JAVA21" rate/PianoRate.java > prove/jdk21.txt
"$JAVA27" -XX:-UseCompactObjectHeaders rate/PianoRate.java > prove/jdk27-senza-header-compatti.txt
"$JAVA27" rate/PianoRate.java > prove/jdk27.txt

for f in prove/jdk21.txt prove/jdk27-senza-header-compatti.txt prove/jdk27.txt; do
    sed -n '1,3p' "$f"
    echo
done

heap_minimo() {
    for mb in $(seq 100 5 250); do
        if "$@" "-Xmx${mb}m" rate/PianoRate.java > /dev/null 2>&1; then
            echo "$mb MB"
            return
        fi
    done
    echo "oltre 250 MB"
}

{
    echo "Heap minimo per caricare tutte le rate (passi da 5 MB)"
    echo "JDK 21:                        $(heap_minimo "$JAVA21")"
    echo "JDK 27 senza header compatti:  $(heap_minimo "$JAVA27" -XX:-UseCompactObjectHeaders)"
    echo "JDK 27:                        $(heap_minimo "$JAVA27")"
} | tee prove/heap-minimo.txt
