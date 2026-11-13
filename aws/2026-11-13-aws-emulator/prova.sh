#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "$@"

ATTESA_MASSIMA=180
PAUSA=1
LETTURE_MASSIME=10
MESSAGGI_PER_LETTURA=10
SECONDI_INVISIBILE=60
PROVE="prove/$DOVE"

API_HOST=$(uscita ApiHost)
CONTI=$(uscita Conti)
CONTABILI=$(uscita Contabili)
BUCKET_CONTABILI=$(uscita BucketContabili)
BONIFICI=$(uscita Bonifici)
SCARTATI=$(uscita Scartati)
NOTIFICHE=$(uscita Notifiche)

righe() (
    aws dynamodb scan --table-name "$1" --select COUNT --query Count --output text
)

messaggi() (
    aws sqs get-queue-attributes --queue-url "$1" --attribute-names ApproximateNumberOfMessages \
        --query Attributes.ApproximateNumberOfMessages --output text
)

apri_conto() (
    aws dynamodb put-item --table-name "$CONTI" \
        --item "{\"conto\": {\"S\": \"$1\"}, \"saldo\": {\"N\": \"$2\"}}"
)

saldo() (
    aws dynamodb get-item --table-name "$CONTI" --key "{\"conto\": {\"S\": \"$1\"}}" \
        --query Item.saldo.N --output text
)

movimento() (
    echo "{\"Update\": {\"TableName\": \"$CONTI\", \"Key\": {\"conto\": {\"S\": \"$1\"}},
        \"UpdateExpression\": \"SET saldo = saldo $2 :importo\", \"ConditionExpression\": \"$3\",
        \"ExpressionAttributeValues\": {\":importo\": {\"N\": \"$4\"}}}}"
)

transazione() (
    da=$1
    a=$2
    importo=$3
    if errore=$(aws dynamodb transact-write-items --transact-items "[
        $(movimento "$da" - "saldo >= :importo" "$importo"),
        $(movimento "$a" + "attribute_exists(conto)" "$importo")]" 2>&1 > /dev/null); then
        echo "eseguita"
    else
        echo "$errore" | solo_errore
    fi
)

bonifico() (
    printf '{"id": "%s", "da": "%s", "a": "%s", "importo": "%s"}' "$@"
)

invia() (
    descrizione=$1
    corpo=$2
    if [ "$DOVE" = emulatore ]; then
        set -- --cacert "$AWS_CA_BUNDLE" -H "Host: $API_HOST" "$AWS_ENDPOINT_URL/Prod/bonifici"
    else
        set -- "https://$API_HOST/Prod/bonifici"
    fi
    curl -sS -H 'content-type: application/json' -d "$corpo" \
        -w '\n%{http_code} %{time_total}\n' "$@" |
        awk -v descrizione="$descrizione" '
            NR == 1 { corpo = $0 }
            NR == 2 { printf "%-46s %s %s in %d ms\n", descrizione, $1, corpo, $2 * 1000 }'
)

aspetta() (
    inizio=$1
    descrizione=$2
    attesi=$3
    shift 3
    scadenza=$(($(date +%s) + ATTESA_MASSIMA))
    trovati=$("$@")
    while [ "$trovati" -lt "$attesi" ] && [ "$(date +%s)" -lt "$scadenza" ]; do
        sleep "$PAUSA"
        trovati=$("$@")
    done
    printf '%-46s %s su %s dopo %d secondi\n' \
        "$descrizione" "$trovati" "$attesi" "$(($(date +%s) - inizio))"
)

leggi_coda() (
    coda=$1
    attesi=$2
    letti=0
    for _ in $(seq "$LETTURE_MASSIME"); do
        if [ "$letti" -ge "$attesi" ]; then
            break
        fi
        nuovi=$(aws sqs receive-message --queue-url "$coda" \
            --max-number-of-messages "$MESSAGGI_PER_LETTURA" --wait-time-seconds 1 \
            --visibility-timeout "$SECONDI_INVISIBILE" --attribute-names ApproximateReceiveCount \
            --output json | jq -c '.Messages // [] | .[]')
        if [ -n "$nuovi" ]; then
            echo "$nuovi"
            letti=$((letti + $(echo "$nuovi" | wc -l)))
        fi
    done
)

if [ "$(righe "$CONTI")" -ne 0 ]; then
    if [ "$DOVE" = emulatore ]; then
        rifare="./ferma-emulatore.sh, ./avvia-emulatore.sh e ./deploy.sh emulatore"
    else
        rifare="./elimina.sh aws e ./deploy.sh aws"
    fi
    echo "Lo stack è già stato usato: lanciate $rifare, poi rifate la prova." >&2
    exit 1
fi
mkdir -p "$PROVE"

echo "1. Apro due conti"
apri_conto 100 1000.00
apri_conto 200 50.00

echo "2. Mando i bonifici all'API e un messaggio scritto a mano sulla coda"
inizio=$(date +%s)
{
    invia "B1: dal 100 al 200, 250.00 euro" "$(bonifico B1 100 200 250.00)"
    invia "B1 di nuovo, stesso id" "$(bonifico B1 100 200 250.00)"
    invia "B2: dal 200 al 100, 5000.00 euro" "$(bonifico B2 200 100 5000.00)"
    invia "B3: dal 100 al conto 999, che non esiste" "$(bonifico B3 100 999 10.00)"
    invia "B4: importo negativo" "$(bonifico B4 100 200 -5)"
    aws sqs send-message --queue-url "$BONIFICI" --message-body "bonifico scritto a mano" \
        --message-group-id a-mano --message-deduplication-id a-mano > /dev/null
} > "$PROVE/api.txt"
cat "$PROVE/api.txt"

echo "3. Aspetto che il giro finisca"
{
    aspetta "$inizio" "Contabili archiviate da S3" 1 righe "$CONTABILI"
    aspetta "$inizio" "Notifiche arrivate da EventBridge" 3 messaggi "$NOTIFICHE"
    aspetta "$inizio" "Messaggi finiti tra gli scartati" 1 messaggi "$SCARTATI"
} > "$PROVE/attese.txt"
cat "$PROVE/attese.txt"

echo "4. Com'è finita"
{
    echo "Saldo del conto numero 100: $(saldo 100)"
    echo "Saldo del conto numero 200: $(saldo 200)"
    file=$(aws s3api list-objects-v2 --bucket "$BUCKET_CONTABILI" \
        --query 'Contents[].Key' --output text)
    echo "File nel bucket delle contabili: $file"
    echo "Contabile archiviata in DynamoDB:"
    aws dynamodb get-item --table-name "$CONTABILI" --key '{"bonifico": {"S": "B1"}}' \
        --query Item.testo.S --output text | sed 's/^/    /'
    echo "Notifiche:"
    leggi_coda "$NOTIFICHE" 3 |
        jq -r '.Body | fromjson | "    \(."detail-type") \(.detail.id) \(.detail.motivo // "")"' |
        sort
    echo "Scartati:"
    leggi_coda "$SCARTATI" 1 |
        jq -r '"    \"\(.Body)\", letto \(.Attributes.ApproximateReceiveCount) volte"'
} > "$PROVE/risultato.txt"
cat "$PROVE/risultato.txt"

echo "5. B2 e B3 rifatti con la CLI, fuori dalla Lambda"
{
    echo "B2, saldo insufficiente: $(transazione 200 100 5000.00)"
    echo "B3, conto 999 inesistente: $(transazione 100 999 10.00)"
} > "$PROVE/cli.txt"
cat "$PROVE/cli.txt"
