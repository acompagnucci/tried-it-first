#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa "$@"

if [ "$DOVE" = emulatore ]; then
    echo "Sull'emulatore basta ./ferma-emulatore.sh: tiene tutto in memoria."
    exit 0
fi

contabili=$(uscita BucketContabili)
aws s3 rm --recursive --quiet "s3://$contabili"
aws cloudformation delete-stack --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --stack-name "$STACK"
aws s3 rb --force "s3://$(bucket_del_codice)" > /dev/null
echo "Tutto cancellato."
