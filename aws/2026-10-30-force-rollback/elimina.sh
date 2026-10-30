#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

for stack in tried-it-first-a-mano tried-it-first-forzato; do
    aws cloudformation delete-stack --region "$REGIONE" --stack-name "$stack"
    aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name "$stack"
done

aws s3 rm --region "$REGIONE" --recursive --quiet "s3://$(bucket)"
aws cloudformation delete-stack --region "$REGIONE" --stack-name tried-it-first-modelli
aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name tried-it-first-modelli
echo "Tutto cancellato."
