#!/bin/sh
set -eu

REGIONE=${AWS_REGION:-eu-west-1}
cd "$(dirname "$0")"

aws cloudformation deploy --region "$REGIONE" \
    --stack-name tried-it-first-codice \
    --template-file codice.yaml

BUCKET=$(aws cloudformation describe-stacks --region "$REGIONE" \
    --stack-name tried-it-first-codice \
    --query "Stacks[0].Outputs[?OutputKey=='Bucket'].OutputValue" --output text)

rm -f web.zip classica.zip
(cd web && zip -q ../web.zip index.js package.json)
(cd classica && zip -q ../classica.zip index.mjs)

versione() {
    aws s3api put-object --region "$REGIONE" --bucket "$BUCKET" --key "$1" --body "$1" \
        --query VersionId --output text
}
VERSIONE_WEB=$(versione web.zip)
VERSIONE_CLASSICA=$(versione classica.zip)

aws cloudformation deploy --region "$REGIONE" \
    --stack-name tried-it-first-web-functions \
    --template-file template.yaml \
    --capabilities CAPABILITY_IAM \
    --parameter-overrides \
        Bucket="$BUCKET" \
        VersioneWeb="$VERSIONE_WEB" \
        VersioneClassica="$VERSIONE_CLASSICA"

aws cloudformation describe-stacks --region "$REGIONE" \
    --stack-name tried-it-first-web-functions \
    --query 'Stacks[0].Outputs' --output table
