#!/bin/sh
set -eu

REGIONE=${AWS_REGION:-eu-south-1}

aws cloudformation delete-stack --region "$REGIONE" --stack-name tried-it-first-giro
aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name tried-it-first-giro

BUCKET=$(aws cloudformation describe-stacks --region "$REGIONE" \
    --stack-name tried-it-first-codice \
    --query "Stacks[0].Outputs[?OutputKey=='Bucket'].OutputValue" --output text)
aws s3 rm --region "$REGIONE" --recursive --quiet "s3://$BUCKET"

aws cloudformation delete-stack --region "$REGIONE" --stack-name tried-it-first-codice
aws cloudformation wait stack-delete-complete --region "$REGIONE" --stack-name tried-it-first-codice

for funzione in protetta libera sdk-2-17-135 sdk-2-21-23 sdk-2-21-24 node sqs; do
    aws logs delete-log-group --region "$REGIONE" --log-group-name "/aws/lambda/tried-it-first-$funzione" 2>/dev/null || true
done
echo "Tutto cancellato."
