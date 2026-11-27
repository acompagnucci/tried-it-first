#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

aws cloudformation delete-stack --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --stack-name "$STACK"
codice=$(bucket_del_codice)
if aws s3api head-bucket --bucket "$codice" 2> /dev/null; then
    aws s3 rb --force "s3://$codice" > /dev/null
fi
echo "Cancellati lo stack $STACK e il bucket del codice."
