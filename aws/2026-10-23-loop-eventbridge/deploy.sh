#!/bin/sh
set -eu

REGIONE=${AWS_REGION:-eu-south-1}
cd "$(dirname "$0")"

./compila.sh

aws cloudformation deploy --region "$REGIONE" \
    --stack-name tried-it-first-codice \
    --template-file codice.yaml

BUCKET=$(aws cloudformation describe-stacks --region "$REGIONE" \
    --stack-name tried-it-first-codice \
    --query "Stacks[0].Outputs[?OutputKey=='Bucket'].OutputValue" --output text)

sam deploy --region "$REGIONE" \
    --stack-name tried-it-first-giro \
    --template-file template.yaml \
    --s3-bucket "$BUCKET" \
    --capabilities CAPABILITY_IAM \
    --no-confirm-changeset \
    --no-fail-on-empty-changeset
