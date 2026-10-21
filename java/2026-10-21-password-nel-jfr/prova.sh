#!/bin/sh
set -eu

if [ -z "${JAVA21:-}" ] || [ -z "${JAVA27:-}" ]; then
    echo "Impostate JAVA21 e JAVA27 con il percorso del comando java di ciascun JDK (vedi README)"
    exit 1
fi

JFR21="$(dirname "$JAVA21")/jfr"
JFR27="$(dirname "$JAVA27")/jfr"
JCMD27="$(dirname "$JAVA27")/jcmd"
PATTERN_AGGIUNTI='-XX:FlightRecorderOptions:redact-key=+*pass*;*url*,redact-argument=+*pass*'

cd "$(dirname "$0")"
CARTELLA="$(pwd)"
mkdir -p prove

avvia() {
    java="$1"
    registrazione="$2"
    attesa="$3"
    shift 3
    env -i PATH="$PATH" PWD="$CARTELLA" \
        DATABASE_URL=postgres://app:SEGRETO4@db:5432/pratiche \
        DB_PASSWORD=SEGRETO5 \
        DB_PASS=SEGRETO6 \
        "$java" "-XX:StartFlightRecording:filename=$registrazione" "$@" \
        "-Ddb.url=jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1" \
        -Ddb.password=SEGRETO2 \
        -Ddb.pass=SEGRETO3 \
        servizio/Servizio.java "$attesa" | grep -v 'jfr,startup'
}

segreti() {
    trovati="$(grep -a -o 'SEGRETO[0-9]' "$1" | sort -u | paste -s -d ' ' -)"
    echo "${trovati:-nessuno}"
}

nel_file() {
    jfr="$1"
    registrazione="$2"
    echo
    echo "Nella registrazione $registrazione:"
    "$jfr" print --events jdk.JVMInformation "$registrazione" | grep -E 'jvmArguments|javaArguments'
    "$jfr" print --events jdk.InitialSystemProperty,jdk.InitialEnvironmentVariable "$registrazione" \
        | grep -A1 -E 'key = "(db\.url|db\.password|db\.pass|DATABASE_URL|DB_PASSWORD|DB_PASS|PWD)"' \
        | grep -v '^--' | paste - - | sed -E 's/^ *key = "([^"]*)"[[:space:]]*value = /  \1 = /'
    echo
    echo "Segreti che si leggono nel file: $(segreti "$registrazione")"
}

prova() {
    java="$1"
    jfr="$2"
    nome="$3"
    shift 3
    {
        avvia "$java" "prove/$nome.jfr" 0 "$@"
        nel_file "$jfr" "prove/$nome.jfr"
    } | sed "s|$CARTELLA|<cartella>|g" > "prove/$nome.txt"
}

prova "$JAVA21" "$JFR21" jdk21
prova "$JAVA27" "$JFR27" jdk27
prova "$JAVA27" "$JFR27" jdk27-pattern-aggiunti "$PATTERN_AGGIUNTI"

"$JFR21" scrub \
    --exclude-events jdk.JVMInformation,jdk.InitialSystemProperty,jdk.InitialEnvironmentVariable \
    prove/jdk21.jfr prove/jdk21-scrub.jfr > /dev/null
echo "Segreti che si leggono in prove/jdk21-scrub.jfr: $(segreti prove/jdk21-scrub.jfr)" > prove/jdk21-scrub.txt

avvia "$JAVA27" prove/jdk27-jcmd.jfr 10 > /dev/null &
PID=""
for tentativo in 1 2 3 4 5 6 7 8; do
    sleep 1
    PID="$("$JCMD27" -l | awk '/servizio\/Servizio.java/ {print $1}')"
    if [ -n "$PID" ]; then
        break
    fi
done
if [ -z "$PID" ]; then
    echo "jcmd non trova il programma lanciato su JDK 27 dopo $tentativo secondi"
    exit 1
fi
{
    echo "jcmd VM.system_properties:"
    "$JCMD27" "$PID" VM.system_properties | grep -E '^db\.'
    echo
    echo "jcmd VM.command_line:"
    "$JCMD27" "$PID" VM.command_line | grep -E '^jvm_args'
} | sed "s|$CARTELLA|<cartella>|g" > prove/jdk27-jcmd.txt
wait

for f in prove/jdk21.txt prove/jdk27.txt prove/jdk27-pattern-aggiunti.txt prove/jdk21-scrub.txt; do
    echo "== $f"
    grep 'Segreti' "$f"
done
