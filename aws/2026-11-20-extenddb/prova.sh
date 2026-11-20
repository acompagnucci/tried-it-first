#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "${1:-}"

LETTURE_PER_SHARD=5
CORSE_IN_PARALLELO=5

record_dello_shard() (
    iteratore=$(aws dynamodbstreams get-shard-iterator --stream-arn "$1" --shard-id "$2" \
        --shard-iterator-type TRIM_HORIZON --query ShardIterator --output text)
    for _ in $(seq "$LETTURE_PER_SHARD"); do
        risposta=$(aws dynamodbstreams get-records --shard-iterator "$iteratore" --output json)
        echo "$risposta" | jq -r '.Records[] | [.dynamodb.Keys.conto.S, .dynamodb.SequenceNumber,
            .eventName, (.dynamodb.OldImage.saldo.N // "-"),
            (.dynamodb.NewImage.saldo.N // "-")] | @tsv'
        iteratore=$(echo "$risposta" | jq -r '.NextShardIterator // empty')
        [ -n "$iteratore" ] || break
    done
)

stream() (
    arn=$(aws dynamodb describe-table --table-name "$CONTI" \
        --query Table.LatestStreamArn --output text)
    shard=$(aws dynamodbstreams describe-stream --stream-arn "$arn" \
        --query 'StreamDescription.Shards[].ShardId' --output text)
    echo "shard: $(echo "$shard" | wc -w | tr -d ' ')"
    printf 'conto\tevento\tsaldo prima\tsaldo dopo\n'
    for id in $shard; do
        record_dello_shard "$arn" "$id"
    done | sort -k1,1 -k2,2n | cut -f1,3-
)

in_parallelo() (
    for corsa in $(seq "$CORSE_IN_PARALLELO"); do
        echo "Corsa $corsa"
        banca "$DOVE" ProvaInParallelo "$CONTI" "$1"
        echo
    done
)

banca "$DOVE" ProvaBonifici "$CONTI" > "$PROVE/01-bonifici.txt"
stream > "$PROVE/02-stream.txt"
if [ "$DOVE" = extenddb ]; then
    ./dentro-postgres.sh > "$PROVE/03-dentro-postgres.txt"
fi
in_parallelo 1 > "$PROVE/04-in-parallelo.txt"
in_parallelo 50 > "$PROVE/05-in-parallelo-con-nuovi-tentativi.txt"
tail -n +1 "$PROVE"/0*.txt
