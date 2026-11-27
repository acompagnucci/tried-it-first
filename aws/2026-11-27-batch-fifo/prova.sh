#!/bin/sh
set -eu

cd "$(dirname "$0")"
mkdir -p prove

prova() (
    nome=$1
    corse=$2
    bonifici=$3
    shift 3
    for corsa in $(seq "$corse"); do
        BONIFICI="$bonifici" ./prova-una.sh "$@" > "prove/$nome-corsa-$corsa.txt"
        echo "prove/$nome-corsa-$corsa.txt"
    done
)

prova 01-batch-da-10 2 10000 dieci 1000
prova 02-batch-da-10000-finestra-1 2 10000 diecimila 1000 1
prova 03-batch-da-10000-finestra-10 2 10000 diecimila 1000 10
prova 04-batch-da-10000-finestra-60 2 10000 diecimila 1000 60
prova 05-un-conto-batch-da-10 1 200 dieci 1
prova 06-un-conto-batch-da-10000-finestra-10 1 200 diecimila 1 10
prova 07-errore-rimanda-il-resto-del-conto 1 10000 resto-del-conto 1000
prova 08-errore-rimanda-solo-il-fallito 1 10000 solo-il-fallito 1000
prova 09-errore-lancia-un-eccezione 1 10000 eccezione 1000
