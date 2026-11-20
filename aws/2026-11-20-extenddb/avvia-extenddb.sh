#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

UTENTE=banca
POLICY='{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "dynamodb:*",
    "Resource": "arn:aws:dynamodb:*:*:table/tried-it-first-*"}]}'

gestisci() {
    compose exec -T extenddb extenddb manage --user admin "$@"
}

crea_utente() (
    account=$(gestisci list-accounts | jq -r '.[0].account_id')
    gestisci create-user --account-id "$account" --user-name "$UTENTE" > /dev/null
    gestisci put-user-policy --account-id "$account" --user-name "$UTENTE" \
        --policy-name tabelle-del-post --policy-document "$POLICY"
    chiave=$(gestisci create-access-key --account-id "$account" --user-name "$UTENTE")
    echo "$chiave" | jq -r '"AWS_ACCESS_KEY_ID=\(.access_key_id)",
        "AWS_SECRET_ACCESS_KEY=\(.secret_access_key)"' > "$CHIAVE"
)

compose up -d --wait
compose exec -T extenddb cat /var/lib/extenddb/.extenddb/tls/cert.pem > "$CERTIFICATO"
rm -f "$TRUSTSTORE"
nel_container keytool -importcert -noprompt -alias extenddb -file "$CERTIFICATO" \
    -keystore "$TRUSTSTORE" -storetype PKCS12 -storepass "$PASSWORD_TRUSTSTORE" > /dev/null

if [ ! -f "$CHIAVE" ]; then
    crea_utente
fi
echo "ExtendDB pronto su https://127.0.0.1:$PORTA, chiave dell'utente $UTENTE in $CHIAVE"
