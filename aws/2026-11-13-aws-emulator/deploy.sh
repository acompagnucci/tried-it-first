#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "$@"

codice=$(bucket_del_codice)
echo "Account $(account), regione $AWS_REGION"

if ! aws s3api head-bucket --bucket "$codice" 2> /dev/null; then
    aws s3 mb "s3://$codice"
fi

mkdir -p "prove/$DOVE"
inizio=$(date +%s)
aws cloudformation package --template-file template.yaml \
    --s3-bucket "$codice" --output-template-file pacchetto.yaml > /dev/null
aws cloudformation deploy --stack-name "$STACK" --template-file pacchetto.yaml \
    --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND
echo "Deploy dello stack: $(($(date +%s) - inizio)) secondi" | tee "prove/$DOVE/deploy.txt"
