#!/bin/sh

REGIONE=${AWS_REGION:-eu-south-1}

risorsa() {
    aws cloudformation describe-stack-resource --region "$REGIONE" \
        --stack-name "$1" --logical-resource-id "$2" \
        --query StackResourceDetail.PhysicalResourceId --output text
}

stato_stack() {
    aws cloudformation describe-stacks --region "$REGIONE" --stack-name "$1" \
        --query 'Stacks[0].StackStatus' --output text
}

bucket() {
    aws cloudformation describe-stacks --region "$REGIONE" \
        --stack-name tried-it-first-modelli \
        --query "Stacks[0].Outputs[?OutputKey=='Bucket'].OutputValue" --output text
}
