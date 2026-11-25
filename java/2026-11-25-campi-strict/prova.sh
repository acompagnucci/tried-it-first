#!/bin/sh
set -eu

CASI="importo banca-prima configurazione-prima"

controlla_jdk() {
    local variabile=$1 java=$2 versione=$3
    if [ -z "$java" ]; then
        echo "Impostate $variabile con il percorso del comando java del JDK $versione (vedi README)"
        exit 1
    fi
    if ! "$java" -version 2>&1 | grep -q "version \"$versione"; then
        echo "$variabile non punta a un JDK $versione:"
        "$java" -version
        exit 1
    fi
}

compila() {
    local versione=$1 java=$2 sorgenti=$3
    shift 3
    "$(dirname "$java")/javac" -Xlint:all,-preview "$@" -d "target/$versione" \
        comune/bonifico/*.java "$sorgenti"/bonifico/*.java
}

rendi_strict() {
    local classi=target/$1/bonifico
    "$JAVA28" -cp target/strumenti strumenti.RendiStrict "$classi/ImportoInValuta.class" \
        centesimi valuta
    "$JAVA28" -cp target/strumenti strumenti.RendiStrict "$classi/Banca.class" ABI
}

salva_flag_dei_campi() {
    local versione=$1 classe
    for classe in Banca Configurazione ImportoInValuta; do
        echo "bonifico.$classe"
        "$BIN28/javap" -v -p -cp "target/$versione" "bonifico.$classe" | awk '
            /version:/ { print }
            /^  [^ ].*;$/ && !/\(/ { campo = $0 }
            /^    flags:/ && campo { print campo; print; campo = "" }'
    done > "prove/$versione-flag.txt"
}

salva_costruttore() {
    local versione=$1
    "$BIN28/javap" -c -p -cp "target/$versione" bonifico.ImportoInValuta |
        sed -n "/ImportoInValuta(long/,/return/p" > "prove/$versione-costruttore.txt"
}

lancia() {
    local versione=$1 java=$2 caso output
    shift 2
    for caso in $CASI; do
        output="prove/$versione-$caso.txt"
        echo "== $versione, $caso"
        "$java" "$@" -cp "target/$versione" bonifico.Prova "$caso" > "$output" 2>&1 ||
            echo "(la JVM è uscita con codice $?)" >> "$output"
        cat "$output"
        if ! grep -q "^Java .*, caso $caso" "$output"; then
            echo "$versione, $caso: il programma non è partito, guardate $output"
            exit 1
        fi
    done
}

controlla_jdk JAVA25 "${JAVA25:-}" 25
controlla_jdk JAVA28 "${JAVA28:-}" 28
BIN28=$(dirname "$JAVA28")

cd "$(dirname "$0")"
rm -rf target prove
mkdir -p prove

"$BIN28/javac" -Xlint:all,-preview -d target/strumenti \
    strumenti/strumenti/RendiStrict.java

compila normali-25 "$JAVA25" normali
lancia normali-25 "$JAVA25"

compila normali-28 "$JAVA28" normali
salva_flag_dei_campi normali-28
salva_costruttore normali-28
lancia normali-28 "$JAVA28"

compila value-28 "$JAVA28" value --release 28 --enable-preview
salva_flag_dei_campi value-28
salva_costruttore value-28
lancia value-28 "$JAVA28" --enable-preview

compila strict-28 "$JAVA28" normali
rendi_strict strict-28
salva_flag_dei_campi strict-28
lancia strict-28 "$JAVA28" --enable-preview

compila corrette-25 "$JAVA25" corrette
lancia corrette-25 "$JAVA25"

compila corrette-strict-28 "$JAVA28" corrette
rendi_strict corrette-strict-28
lancia corrette-strict-28 "$JAVA28" --enable-preview
