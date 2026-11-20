#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

sql() {
    database=$1
    shift
    compose exec -T postgres psql -U postgres -d "$database" "$@"
}

ID_CONTI=$(sql extenddb_catalog -At -c "select table_id from tables where table_name = '$CONTI'")

echo "Le tabelle DynamoDB nel catalogo:"
sql extenddb_catalog -c "select table_name, table_id, ttl_attribute from tables order by table_name"

echo "I conti nella tabella _ddb_$ID_CONTI:"
sql extenddb -c "select pk, item_data from \"_ddb_$ID_CONTI\" order by pk"

echo "Lo stream della tabella dei conti:"
sql extenddb -c "select event_name, record_data #>> '{dynamodb,Keys,conto,S}' as conto,
        record_data #>> '{dynamodb,OldImage,saldo,N}' as saldo_prima,
        record_data #>> '{dynamodb,NewImage,saldo,N}' as saldo_dopo, created_at
    from stream_records where table_id = '$ID_CONTI' order by created_at"

echo "L'ultimo token salvato, quello del bonifico 1:"
sql extenddb -c "select token, fingerprint, created_at from idempotency_tokens
    order by created_at desc limit 1"
