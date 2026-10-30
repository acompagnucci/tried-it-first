#!/bin/sh
set -eu

STACK=${1:?"uso: ./deploy.sh nome-stack [attesa]"}
ATTESA=${2:-30}
cd "$(dirname "$0")"
. ./comune.sh

aws cloudformation deploy --region "$REGIONE" \
    --stack-name tried-it-first-modelli \
    --template-file bucket.yaml \
    --no-fail-on-empty-changeset

mkdir -p pacchetto
aws cloudformation package --region "$REGIONE" \
    --template-file template.yaml \
    --s3-bucket "$(bucket)" \
    --output-template-file pacchetto/template.yaml > /dev/null

aws cloudformation deploy --region "$REGIONE" \
    --stack-name "$STACK" \
    --template-file pacchetto/template.yaml \
    --parameter-overrides Attesa="$ATTESA" \
    --no-fail-on-empty-changeset
