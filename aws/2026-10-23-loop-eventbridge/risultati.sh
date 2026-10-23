#!/bin/sh
set -eu

REGIONE=${AWS_REGION:-eu-south-1}
cd "$(dirname "$0")"
mkdir -p "prove/$REGIONE"
FILE="prove/$REGIONE/fermati.txt"

DA=$(date -u -v-3H +%Y-%m-%dT%H:%M:00Z 2>/dev/null || date -u -d '3 hours ago' +%Y-%m-%dT%H:%M:00Z)
A=$(date -u +%Y-%m-%dT%H:%M:00Z)

echo "RecursiveInvocationsDropped dalle $DA alle $A" > "$FILE"
for funzione in protetta libera sdk-2-17-135 sdk-2-21-23 sdk-2-21-24 node sqs; do
    bloccate=$(aws cloudwatch get-metric-statistics --region "$REGIONE" --namespace AWS/Lambda \
        --metric-name RecursiveInvocationsDropped \
        --dimensions Name=FunctionName,Value="tried-it-first-$funzione" \
        --start-time "$DA" --end-time "$A" --period 10800 --statistics Sum \
        --query 'Datapoints[0].Sum' --output text)
    echo "  tried-it-first-$funzione: $bloccate" >> "$FILE"
done

CODA=$(aws sqs get-queue-url --region "$REGIONE" --queue-name tried-it-first-fermati --output text)
echo "Corpo dei messaggi nella coda tried-it-first-fermati:" >> "$FILE"
aws sqs receive-message --region "$REGIONE" --queue-url "$CODA" \
    --max-number-of-messages 10 --visibility-timeout 60 \
    --query 'Messages[].Body' --output text >> "$FILE"
cat "$FILE"
