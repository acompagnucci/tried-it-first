#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
    echo "Uso: $0 dieci|diecimila|resto-del-conto|solo-il-fallito|eccezione conti [finestra]" >&2
    exit 2
fi
FUNZIONE=$1
NUMERO_DI_CONTI=$2
FINESTRA=${3:-10}
BONIFICI=${BONIFICI:-10000}
PAUSA=5
CONTROLLI_A_CODA_VUOTA=3
ATTESA_MASSIMA=1800

stato_della_lettura() (
    aws lambda get-event-source-mapping --uuid "$1" --query State --output text
)

aspetta_la_lettura() (
    until [ "$(stato_della_lettura "$1")" = "$2" ]; do
        sleep "$PAUSA"
    done
)

cambia_lettura() (
    uuid=$1
    stato=$2
    shift 2
    aws lambda update-event-source-mapping --uuid "$uuid" "$@" > /dev/null
    aspetta_la_lettura "$uuid" "$stato"
)

messaggi_in_coda() (
    aws sqs get-queue-attributes --queue-url "$1" \
        --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
        --query 'Attributes.[ApproximateNumberOfMessages, ApproximateNumberOfMessagesNotVisible]' \
        --output text | awk '{ print $1 + $2 }'
)

aspetta_la_coda_vuota() (
    inizio=$(date +%s)
    vuota=0
    while [ "$vuota" -lt "$CONTROLLI_A_CODA_VUOTA" ]; do
        if [ $(($(date +%s) - inizio)) -gt "$ATTESA_MASSIMA" ]; then
            echo "La coda non si è svuotata in $ATTESA_MASSIMA secondi" >&2
            exit 1
        fi
        sleep "$PAUSA"
        if [ "$(messaggi_in_coda "$1")" -eq 0 ]; then
            vuota=$((vuota + 1))
        else
            vuota=0
        fi
    done
)

coda=$(url_della_coda "tried-it-first-$FUNZIONE.fifo")
lettura=$(aws lambda list-event-source-mappings --function-name "tried-it-first-$FUNZIONE" \
    --query 'EventSourceMappings[0].UUID' --output text)
prova="$FUNZIONE-$NUMERO_DI_CONTI-$(date +%Y%m%d-%H%M%S)"

if [ "$FUNZIONE" != dieci ]; then
    cambia_lettura "$lettura" Disabled --maximum-batching-window-in-seconds "$FINESTRA"
fi
aws lambda get-event-source-mapping --uuid "$lettura" --output json \
    --query '{batch: BatchSize, finestra: MaximumBatchingWindowInSeconds,
        poller: ProvisionedPollerConfig}' | jq -c .
echo "Funzione tried-it-first-$FUNZIONE, prova $prova"
bonifici Invia "$coda" "$prova" "$NUMERO_DI_CONTI" "$BONIFICI"
trap 'cambia_lettura "$lettura" Disabled --no-enabled' EXIT
cambia_lettura "$lettura" Enabled --enabled
aspetta_la_coda_vuota "$coda"
cambia_lettura "$lettura" Disabled --no-enabled
trap - EXIT
bonifici Riassunto "$CONTI" "$INVOCAZIONI" "$prova"
echo "Nella coda dei falliti: $(messaggi_in_coda "$(url_della_coda "$FALLITI")")"
