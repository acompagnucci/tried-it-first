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

if curl -s -o /dev/null http://localhost:8080; then
    echo "Sulla porta 8080 risponde già qualcosa: fermatelo prima di lanciare la prova"
    exit 1
fi

cd "$(dirname "$0")"
mkdir -p prove

echo "== build del servizio"
(cd servizio && JAVA_HOME="$(dirname "$(dirname "$JAVA28")")" ./mvnw -q -B package)
rm -rf servizio/target/estratto
"$JAVA28" -Djarmode=tools -jar servizio/target/bonifici.jar extract --destination servizio/target/estratto

echo "== addestramento"
"$JAVA28" misura/Misura.java addestra | tee prove/addestramento.txt

echo "== confronto"
"$JAVA28" misura/Misura.java confronta | tee prove/confronto.txt

echo "== la cache addestrata con G1, con altri garbage collector"
"$JAVA28" misura/Misura.java altri-gc | tee prove/altri-gc.txt
