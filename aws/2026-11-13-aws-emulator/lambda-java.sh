#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh
usa emulatore

FUNZIONE=tried-it-first-saluto-java
RUOLO=tried-it-first-saluto-java
LAVORO=$(mktemp -d)
trap 'rm -rf "$LAVORO"' EXIT

javac --release 21 -d "$LAVORO/classi" java/saluto/Saluto.java
(cd "$LAVORO/classi" && zip -qr ../saluto.zip .)

ruolo=$(aws iam create-role --role-name "$RUOLO" --query Role.Arn --output text \
    --assume-role-policy-document '{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
        "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}')
aws lambda create-function --function-name "$FUNZIONE" --runtime java21 --role "$ruolo" \
    --handler saluto.Saluto::saluta --zip-file "fileb://$LAVORO/saluto.zip" > /dev/null
aws lambda wait function-active-v2 --function-name "$FUNZIONE"

mkdir -p prove/emulatore
{
    echo "Stato della funzione: $(aws lambda get-function --function-name "$FUNZIONE" \
        --query 'Configuration.[Runtime, State]' --output text)"
    echo "Invocazione:"
    aws lambda invoke --function-name "$FUNZIONE" --payload '{"conto": "100"}' \
        --cli-binary-format raw-in-base64-out "$LAVORO/risposta.json" 2>&1 > /dev/null |
        solo_errore | sed 's/^/    /'
} > prove/emulatore/lambda-java.txt
cat prove/emulatore/lambda-java.txt

aws lambda delete-function --function-name "$FUNZIONE" > /dev/null
aws iam delete-role --role-name "$RUOLO"
