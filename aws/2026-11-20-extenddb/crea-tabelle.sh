#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "${1:-}"

crea() (
    tabella=$1
    chiave=$2
    shift 2
    aws dynamodb create-table --table-name "$tabella" \
        --attribute-definitions "AttributeName=$chiave,AttributeType=S" \
        --key-schema "AttributeName=$chiave,KeyType=HASH" \
        --billing-mode PAY_PER_REQUEST "$@" > /dev/null
    aws dynamodb wait table-exists --table-name "$tabella"
)

crea "$CONTI" conto --stream-specification StreamEnabled=true,StreamViewType=NEW_AND_OLD_IMAGES
crea "$MOVIMENTI" id
aws dynamodb update-time-to-live --table-name "$MOVIMENTI" \
    --time-to-live-specification Enabled=true,AttributeName=scade > /dev/null
for tabella in "$CONTI" "$MOVIMENTI"; do
    aws dynamodb describe-table --table-name "$tabella" \
        --query 'Table.[TableName, TableStatus, StreamSpecification.StreamViewType]' --output text
done
aws dynamodb describe-time-to-live --table-name "$MOVIMENTI" \
    --query 'TimeToLiveDescription.[TimeToLiveStatus, AttributeName]' --output text
