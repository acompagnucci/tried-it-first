#!/bin/sh
set -eu

REGIONE=${AWS_REGION:-eu-west-1}

aws cloudformation delete-stack --region "$REGIONE" --stack-name tried-it-first-web-functions
aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name tried-it-first-web-functions

BUCKET=$(aws cloudformation describe-stacks --region "$REGIONE" \
    --stack-name tried-it-first-codice \
    --query "Stacks[0].Outputs[?OutputKey=='Bucket'].OutputValue" --output text)

aws s3api list-object-versions --region "$REGIONE" --bucket "$BUCKET" \
    --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}}' --output json > /tmp/versioni.json
if grep -q VersionId /tmp/versioni.json; then
    aws s3api delete-objects --region "$REGIONE" --bucket "$BUCKET" --delete file:///tmp/versioni.json > /dev/null
fi
rm -f /tmp/versioni.json

aws cloudformation delete-stack --region "$REGIONE" --stack-name tried-it-first-codice
aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name tried-it-first-codice

for gruppo in /aws/lambda/web/tried-it-first-web /aws/lambda/web/tried-it-first-web.local /aws/lambda/tried-it-first-classica; do
    aws logs delete-log-group --region "$REGIONE" --log-group-name "$gruppo" 2>/dev/null || true
done
echo "Tutto cancellato."
