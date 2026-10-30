#!/bin/sh
set -eu

STACK=${1:?"uso: ./rompi.sh nome-stack"}
cd "$(dirname "$0")"
. ./comune.sh

BONIFICI=$(risorsa "$STACK" Bonifici)
AVVISI=$(risorsa "$(risorsa "$STACK" Notifiche)" Avvisi)

aws sqs delete-queue --region "$REGIONE" --queue-url "$BONIFICI"
aws sqs delete-queue --region "$REGIONE" --queue-url "$AVVISI"
echo "Cancellate a mano, fuori da CloudFormation:"
echo "  $BONIFICI"
echo "  $AVVISI"
