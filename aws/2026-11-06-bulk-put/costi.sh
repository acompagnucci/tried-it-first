#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

PROVE="prove/$REGIONE"
REGIONE_DEI_PREZZI=eu-south-1
DOLLARI_PER_GB_BULK=0.1184
DOLLARI_PER_MILIONE_DI_SCRITTURE=0.74
BYTE_PER_GB=1073741824
SCRITTURE_PER_MILIONE=1000000

if [ "$REGIONE" != "$REGIONE_DEI_PREZZI" ]; then
    echo "I prezzi in costi.sh sono quelli di $REGIONE_DEI_PREZZI: per $REGIONE cambiateli dal listino AWS." >&2
fi

inizio=$(jq -r .BulkOperationDescription.StartTime "$PROVE/02-giorno-senza-report.json")
scritture=$(aws cloudwatch get-metric-statistics --region "$REGIONE" \
    --namespace AWS/DynamoDB --metric-name ConsumedWriteCapacityUnits \
    --dimensions Name=TableName,Value=tried-it-first-put-item \
    --start-time "$inizio" --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --period 86400 --statistics Sum --query 'Datapoints[0].Sum' --output text)
if [ "$scritture" = None ]; then
    echo "CloudWatch non ha ancora le scritture della PutItem: riprovate tra qualche minuto." >&2
    exit 1
fi

for file in "$PROVE"/02-*.json; do
    jq -r '.BulkOperationDescription | [.TableDetails[0].TableName, .ProcessedItemCount, .BulkOperationUsage[0].BulkPutBytes] | @tsv' "$file"
done | while read -r tabella righe byte; do
    echo "$tabella, bulk put: $righe righe lette, $byte byte, $(echo "$byte $BYTE_PER_GB $DOLLARI_PER_GB_BULK" | awk '{ printf "%.5f", $1 / $2 * $3 }') dollari"
done > "$PROVE/05-costi.txt"
echo "tried-it-first-put-item, PutItem: $scritture unità di scrittura, $(echo "$scritture $SCRITTURE_PER_MILIONE $DOLLARI_PER_MILIONE_DI_SCRITTURE" | awk '{ printf "%.5f", $1 / $2 * $3 }') dollari" >> "$PROVE/05-costi.txt"

cat "$PROVE/05-costi.txt"
