#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

PROVE="prove/$REGIONE"
CONTROLLI=180
PAUSA=10
MOVIMENTI_INIZIALI=10000
SOLO_ERRORI='["ERROR"]'
TUTTI_I_REPORT='["ERROR","CONDITION_CHECK_FAILURE"]'
BUCKET=$(bucket)
mkdir -p "$PROVE"
: > "$PROVE/tempi.txt"

aspetta() (
    for _ in $(seq "$CONTROLLI"); do
        stato=$(aws dynamodb describe-bulk-operation --region "$REGIONE" --bulk-operation-arn "$1" \
            --query BulkOperationDescription.BulkOperationStatus --output text)
        case $stato in
            COMPLETED | COMPLETED_WITH_ERRORS | CANCELLED | FAILED) return 0 ;;
            *) sleep "$PAUSA" ;;
        esac
    done
    echo "$1 ancora in corso dopo $((CONTROLLI * PAUSA)) secondi" >&2
    return 1
)

bulk_put() (
    tabella=$1
    cartella=$2
    report=$3
    passo=$4
    inizio=$(date +%s)
    arn=$(aws dynamodb start-bulk-put-items --region "$REGIONE" \
        --table-name "tried-it-first-$tabella" \
        --input-s3-configuration "{\"S3Bucket\": \"$BUCKET\", \"S3KeyPrefix\": \"ingresso/$cartella/\", \"InputFormat\": \"DYNAMODB_JSON\", \"InputCompressionType\": \"NONE\"}" \
        --output-s3-configuration "{\"S3Bucket\": \"$BUCKET\", \"S3KeyPrefix\": \"report/$passo-$tabella/\", \"OutputType\": $report}" \
        --query BulkOperationDescription.BulkOperationArn --output text)
    aspetta "$arn"
    echo "$passo $tabella: $(($(date +%s) - inizio)) secondi" >> "$PROVE/tempi.txt"
    aws dynamodb describe-bulk-operation --region "$REGIONE" --bulk-operation-arn "$arn" \
        --output json > "$PROVE/$passo-$tabella.json"
)

put_item() (
    inizio=$(date +%s)
    eval "$(aws configure export-credentials --format env)"
    java_in_docker -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
        -e AWS_REGION="$REGIONE" "$IMMAGINE" \
        java -cp carica/target/carica.jar carica.CaricaConPutItem tried-it-first-put-item \
        > "$PROVE/02-giorno-put-item.txt"
    echo "02-giorno put-item: $(($(date +%s) - inizio)) secondi" >> "$PROVE/tempi.txt"
)

aspetta_tutti() {
    for processo in "$@"; do
        wait "$processo"
    done
}

controlla() (
    trovati=$(conta "$1")
    if [ "$trovati" -ne "$2" ]; then
        echo "tried-it-first-$1: mi aspettavo $2 movimenti, ne ho trovati $trovati." >&2
        exit 1
    fi
)

if ! docker info > /dev/null 2>&1; then
    echo "Docker non risponde: serve per il confronto con PutItem. Avviatelo e rilanciate." >&2
    exit 1
fi

echo "Carico i file su S3"
aws s3 sync --region "$REGIONE" --quiet --delete file "s3://$BUCKET/ingresso"

echo "1. I 10.000 movimenti di partenza, in tre tabelle"
bulk_put senza-report iniziali "$SOLO_ERRORI" 01-iniziali & a=$!
bulk_put con-report iniziali "$SOLO_ERRORI" 01-iniziali & b=$!
bulk_put put-item iniziali "$SOLO_ERRORI" 01-iniziali & c=$!
aspetta_tutti "$a" "$b" "$c"
for tabella in senza-report con-report put-item; do
    controlla "$tabella" "$MOVIMENTI_INIZIALI"
done

echo "2. Il file del giorno: bulk put senza report, bulk put con report, PutItem; le righe rotte"
bulk_put senza-report giorno "$SOLO_ERRORI" 02-giorno & a=$!
bulk_put con-report giorno "$TUTTI_I_REPORT" 02-giorno & b=$!
bulk_put righe-rotte righe-rotte "$TUTTI_I_REPORT" 02-righe-rotte & c=$!
put_item & d=$!
aspetta_tutti "$a" "$b" "$c" "$d"

echo "3. Cosa c'è nelle tabelle"
{
    for tabella in senza-report con-report put-item; do
        echo "$tabella: $(conta "$tabella") movimenti, $(conta "$tabella" file '{"S": "giorno"}') dal file del giorno, $(conta "$tabella" versione '{"N": "2"}') alla versione 2"
    done
    echo "righe-rotte: $(conta righe-rotte) movimenti, $(conta righe-rotte file '{"S": "senza-chiave"}') da senza-chiave.json, $(conta righe-rotte file '{"S": "troncata"}') da troncata.json"
} > "$PROVE/03-conteggi.txt"

echo "4. I report su S3"
rm -rf "$PROVE/report"
aws s3 sync --region "$REGIONE" --quiet "s3://$BUCKET/report" "$PROVE/report"
find "$PROVE/report" -name '*.gz' -exec gunzip {} +
find "$PROVE/report" -type f | sort | while read -r file; do
    echo "$(wc -l < "$file" | tr -d ' ') righe  ${file#"$PROVE/report/"}"
done > "$PROVE/04-report.txt"

cat "$PROVE/tempi.txt" "$PROVE/03-conteggi.txt" "$PROVE/04-report.txt"
