#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

aws cloudformation deploy --region "$REGIONE" \
    --stack-name "$STACK" \
    --template-file template.yaml \
    --no-fail-on-empty-changeset
