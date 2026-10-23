#!/bin/sh
set -eu

REGIONE=${AWS_REGION:-eu-south-1}
PROVA=${1:?"uso: ./prova.sh protetta|libera|sdk-2.17.135|sdk-2.21.23|sdk-2.21.24|node|sqs"}
GRUPPO="/aws/lambda/tried-it-first-$(echo "$PROVA" | tr . -)"
cd "$(dirname "$0")"
mkdir -p "prove/$REGIONE"

INIZIO=$(($(date +%s) * 1000))
if [ "$PROVA" = sqs ]; then
    CODA=$(aws sqs get-queue-url --region "$REGIONE" --queue-name tried-it-first-giro-sqs --output text)
    aws sqs send-message --region "$REGIONE" --queue-url "$CODA" --message-body 1 > /dev/null
else
    aws events put-events --region "$REGIONE" --entries "[{
        \"EventBusName\": \"tried-it-first-giro\",
        \"Source\": \"tried-it-first\",
        \"DetailType\": \"giro\",
        \"Detail\": \"{\\\"prova\\\":\\\"$PROVA\\\",\\\"giro\\\":1}\"
    }]" > /dev/null
fi
echo "Partito il primo giro di $PROVA, aspetto che si fermi..."

giri() {
    aws logs filter-log-events --region "$REGIONE" --log-group-name "$GRUPPO" \
        --start-time "$INIZIO" --filter-pattern '"prova="' \
        --query 'events[].[timestamp, message]' --output text | grep 'prova='
}

precedenti=-1
fermi=0
for _ in $(seq 60); do
    sleep 5
    adesso=$(giri 2>/dev/null | wc -l | tr -d ' ')
    if [ "$adesso" -eq "$precedenti" ] && [ "$adesso" -gt 0 ]; then
        fermi=$((fermi + 1))
        [ "$fermi" -ge 6 ] && break
    else
        fermi=0
    fi
    precedenti=$adesso
done

giri | sort -n > "prove/$REGIONE/$PROVA.txt"
echo "$PROVA: $(wc -l < "prove/$REGIONE/$PROVA.txt" | tr -d ' ') giri, dettaglio in prove/$REGIONE/$PROVA.txt"
