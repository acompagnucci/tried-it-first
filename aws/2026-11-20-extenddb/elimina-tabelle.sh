#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "${1:-}"

for tabella in "$CONTI" "$MOVIMENTI"; do
    if aws dynamodb describe-table --table-name "$tabella" > /dev/null 2>&1; then
        aws dynamodb delete-table --table-name "$tabella" > /dev/null
        aws dynamodb wait table-not-exists --table-name "$tabella"
        echo "cancellata $tabella"
    fi
done
