#!/bin/sh
set -eu

PETCLINIC=https://github.com/spring-projects/spring-petclinic.git
PETCLINIC_COMMIT=500158f732419217507c7656904b8e6aa1bcc0d6

controlla_java() {
    variabile=$1
    java=$2
    versione=$3
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

heap_predefinito() {
    nome=$1
    java=$2
    shift 2
    "$java" "$@" -XX:+PrintFlagsFinal -version 2>/dev/null |
        awk -v scenario="$nome $*" -v mb=1048576 '
            $2 == "InitialHeapSize" { iniziale = $4 / mb }
            $2 == "MaxHeapSize" { massimo = $4 / mb }
            END { printf "%s: heap iniziale %d MB, massimo %d MB\n", scenario, iniziale, massimo }'
}

controlla_java JAVA25 "${JAVA25:-}" 25
controlla_java JAVA28 "${JAVA28:-}" 28

if curl -s -o /dev/null http://localhost:8080; then
    echo "Sulla porta 8080 risponde già qualcosa: fermatelo prima di lanciare la prova"
    exit 1
fi

cd "$(dirname "$0")"

if [ ! -d target/petclinic ]; then
    echo "== download di Spring PetClinic"
    git clone -q "$PETCLINIC" target/petclinic
fi
git -C target/petclinic checkout -q "$PETCLINIC_COMMIT"

echo "== build di PetClinic con il JDK 25"
(cd target/petclinic && JAVA_HOME="$(dirname "$(dirname "$JAVA25")")" ./mvnw -q -B -DskipTests package)
cp target/petclinic/target/spring-petclinic-*.jar target/petclinic.jar

mkdir -p prove

echo "== heap iniziale e massimo senza -Xms e -Xmx"
{
    heap_predefinito "Java 25" "$JAVA25" -XX:+UseZGC
    heap_predefinito "Java 28" "$JAVA28" -XX:+UseZGC
    heap_predefinito "Java 28" "$JAVA28" -XX:+UseZGC -XX:+ZAdaptiveHeapSizing
} | tee prove/heap-predefinito.txt

echo "== confronto"
"$JAVA25" misura/Misura.java "$@" | tee prove/confronto.txt

echo "== cicli del GC"
"$JAVA25" misura/CicliDelGc.java | tee prove/cicli-gc.txt
