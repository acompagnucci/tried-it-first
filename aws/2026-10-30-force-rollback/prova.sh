#!/bin/sh
set -eu

MODO=${1:-}
case $MODO in
    a-mano | forzato) ;;
    *) echo "uso: ./prova.sh a-mano|forzato" >&2; exit 1 ;;
esac
cd "$(dirname "$0")"
. ./comune.sh

STACK="tried-it-first-$MODO"
PROVE="prove/$MODO"
PRIMA=30
DOPO=60
CONTROLLI=120
PAUSA=5
mkdir -p "$PROVE"
: > "$PROVE/tempi.txt"

aspetta() {
    for _ in $(seq "$CONTROLLI"); do
        case $(stato_stack "$1") in
            *IN_PROGRESS) sleep "$PAUSA" ;;
            *) return 0 ;;
        esac
    done
    echo "$1 ancora in corso dopo $((CONTROLLI * PAUSA)) secondi" >&2
    return 1
}

salva() {
    ./stato.sh "$STACK" > "$PROVE/$1.txt"
    echo "$1: $(head -1 "$PROVE/$1.txt" | cut -f2)"
}

deploy_che_blocca() (
    if ./deploy.sh "$STACK" "$DOPO" > /dev/null 2>&1; then
        echo "Il deploy doveva fallire e invece è andato." >&2
        exit 1
    fi
    stato=$(stato_stack "$STACK")
    if [ "$stato" != UPDATE_ROLLBACK_FAILED ]; then
        echo "Mi aspettavo UPDATE_ROLLBACK_FAILED, lo stack è in $stato." >&2
        exit 1
    fi
)

sblocca() (
    inizio=$(date +%s)
    if [ "$MODO" = forzato ]; then
        aws cloudformation continue-update-rollback --region "$REGIONE" \
            --stack-name "$STACK" --force-rollback
    else
        aws cloudformation continue-update-rollback --region "$REGIONE" \
            --stack-name "$STACK" --resources-to-skip "$1"
    fi
    aspetta "$STACK"
    echo "$2: $(($(date +%s) - inizio)) secondi" >> "$PROVE/tempi.txt"
)

change_set() (
    stack=$1
    nome=$2
    shift 2
    aws cloudformation create-change-set --region "$REGIONE" \
        --stack-name "$stack" --change-set-name "$nome" \
        --deployment-mode REVERT_DRIFT "$@" > /dev/null
    aws cloudformation wait change-set-create-complete --region "$REGIONE" \
        --stack-name "$stack" --change-set-name "$nome"
    aws cloudformation describe-change-set --region "$REGIONE" \
        --stack-name "$stack" --change-set-name "$nome" \
        --query 'Changes[].ResourceChange.[Action, LogicalResourceId, ResourceDriftStatus]' --output text
    aws cloudformation execute-change-set --region "$REGIONE" \
        --stack-name "$stack" --change-set-name "$nome"
    aspetta "$stack"
)

annidato() {
    risorsa "$STACK" Notifiche
}

./deploy.sh "$STACK" "$PRIMA"
salva 01-creato

./rompi.sh "$STACK" > "$PROVE/02-code-cancellate.txt"
deploy_che_blocca
salva 03-bloccato

sblocca Bonifici "primo sblocco"
salva 04-sbloccato

deploy_che_blocca
salva 05-di-nuovo-bloccato

sblocca Bonifici "secondo sblocco"
salva 06-sbloccato

change_set "$STACK" ripara --template-body file://pacchetto/template.yaml \
    --parameters ParameterKey=Attesa,ParameterValue="$DOPO" > "$PROVE/07-change-set.txt"
salva 08-dopo-change-set

sblocca "$(annidato | cut -d/ -f2).Avvisi" "terzo sblocco"
salva 09-sbloccato
aws cloudformation describe-stack-events --region "$REGIONE" --stack-name "$STACK" \
    --query "StackEvents[?LogicalResourceId=='Bonifici'].[ResourceStatus, ResourceStatusReason]" \
    --output text | head -2 >> "$PROVE/09-sbloccato.txt"

change_set "$(annidato)" ripara-annidato --use-previous-template \
    --parameters ParameterKey=Attesa,UsePreviousValue=true > "$PROVE/10-change-set-annidato.txt"
change_set "$STACK" ripara-tutto --template-body file://pacchetto/template.yaml \
    --parameters ParameterKey=Attesa,ParameterValue="$DOPO" > "$PROVE/11-change-set.txt"
salva 12-riparato

for coda in $(aws sqs list-queues --region "$REGIONE" --queue-name-prefix "$STACK" --query QueueUrls --output text); do
    echo "$coda $(aws sqs get-queue-attributes --region "$REGIONE" --queue-url "$coda" \
        --attribute-names VisibilityTimeout --query Attributes.VisibilityTimeout --output text)"
done >> "$PROVE/12-riparato.txt"
cat "$PROVE/tempi.txt"
