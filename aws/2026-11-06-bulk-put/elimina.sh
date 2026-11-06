#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

aws s3 rm --region "$REGIONE" --recursive --quiet "s3://$(bucket)"
aws cloudformation delete-stack --region "$REGIONE" --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name "$STACK"
echo "Tutto cancellato."
