#!/bin/sh
set -eu

STACK=${1:?"uso: ./stato.sh nome-stack"}
cd "$(dirname "$0")"
. ./comune.sh

stato() {
    aws cloudformation describe-stacks --region "$REGIONE" --stack-name "$1" \
        --query 'Stacks[0].[StackName, StackStatus, StackStatusReason]' --output text
    aws cloudformation describe-stack-resources --region "$REGIONE" --stack-name "$1" \
        --query 'StackResources[].[LogicalResourceId, ResourceStatus, ResourceStatusReason]' --output text |
        sed 's/^/    /'
}

stato "$STACK"
stato "$(risorsa "$STACK" Notifiche)"
