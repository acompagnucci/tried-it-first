#!/bin/sh
set -eu

cd "$(dirname "$0")"

EMULATORE=aws-emulator
PORTA=9297
CARTELLA="$HOME/.aws/emulator/$PORTA"
ATTESA_MASSIMA=30
FUNZIONE=tried-it-first-controllo-java
CODA=tried-it-first-controllo.fifo
BUCKET=tried-it-first-controllo
EVENTO='{"Records": [{"messageId": "1", "attributes": {"ApproximateReceiveCount": "1"}, "body":
    "{\"prova\": \"emulatore\", \"conto\": 1, \"progressivo\": 1, \"centesimi\": 10000}"}]}'

sull_emulatore() (
    env -i HOME="$HOME" PATH="$PATH" AWS_PAGER="" \
        AWS_SHARED_CREDENTIALS_FILE="$CARTELLA/emulator_credentials" \
        AWS_CONFIG_FILE="$CARTELLA/config" aws "$@"
)

solo_errore() (
    sed -n 's/.*when calling the [A-Za-z]* operation\( ([^)]*)\)\{0,1\}: \(.*\)/\2/p'
)

avvia() {
    case "$(uname -m)" in
        arm64 | aarch64) architettura=aarch64 ;;
        *) architettura=x86_64 ;;
    esac
    docker build --quiet --pull --build-arg ARCHITETTURA="$architettura" -t "$EMULATORE" emulatore \
        > /dev/null
    mkdir -p "$HOME/.aws/emulator"
    docker run --detach --rm --name "$EMULATORE" -p "127.0.0.1:$PORTA:$PORTA" \
        -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" \
        "$EMULATORE" -p "$PORTA" --telemetry off > /dev/null
    trap 'docker stop "$EMULATORE" > /dev/null' EXIT
    for _ in $(seq "$ATTESA_MASSIMA"); do
        if curl -sf --cacert "$CARTELLA/ca.pem" "https://127.0.0.1:$PORTA/__admin/health" \
            > /dev/null; then
            return
        fi
        sleep 1
    done
    echo "L'emulatore non risponde dopo $ATTESA_MASSIMA secondi: docker logs $EMULATORE" >&2
    exit 1
}

lambda_java() (
    ruolo=$(sull_emulatore iam create-role --role-name "$FUNZIONE" --query Role.Arn --output text \
        --assume-role-policy-document '{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
            "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}')
    sull_emulatore iam put-role-policy --role-name "$FUNZIONE" --policy-name coda \
        --policy-document '{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
            "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
            "Resource": "*"}]}'
    sull_emulatore s3 mb "s3://$BUCKET" > /dev/null
    sull_emulatore s3 cp --quiet bonifici/target/bonifici.jar "s3://$BUCKET/bonifici.jar"
    sull_emulatore lambda create-function --function-name "$FUNZIONE" --runtime java21 \
        --role "$ruolo" --handler bonifici.RimandaIlRestoDelConto::handleRequest \
        --code "S3Bucket=$BUCKET,S3Key=bonifici.jar" > /dev/null
    sull_emulatore lambda wait function-active-v2 --function-name "$FUNZIONE"
    risposta=$(mktemp)
    trap 'rm -f "$risposta"' EXIT
    echo "Lambda java21 con bonifici.jar, invocata con un bonifico:"
    if errore=$(sull_emulatore lambda invoke --function-name "$FUNZIONE" \
        --cli-binary-format raw-in-base64-out --payload "$EVENTO" "$risposta" \
        2>&1 > /dev/null); then
        echo "  risponde: $(cat "$risposta")"
    else
        echo "  $(echo "$errore" | solo_errore)"
    fi
)

lettura_da_diecimila() (
    coda=$(sull_emulatore sqs create-queue --queue-name "$CODA" --attributes FifoQueue=true \
        --query QueueUrl --output text)
    arn=$(sull_emulatore sqs get-queue-attributes --queue-url "$coda" --attribute-names QueueArn \
        --query Attributes.QueueArn --output text)
    echo "Event source mapping FIFO, provisioned mode, batch da 10.000 e finestra da 10 secondi:"
    if errore=$(sull_emulatore lambda create-event-source-mapping --function-name "$FUNZIONE" \
        --event-source-arn "$arn" --batch-size 10000 --maximum-batching-window-in-seconds 10 \
        --provisioned-poller-config MinimumPollers=2,MaximumPollers=10 --no-enabled \
        2>&1 > /dev/null); then
        echo "  accettato"
    else
        echo "  $(echo "$errore" | solo_errore)"
    fi
)

if [ ! -f bonifici/target/bonifici.jar ]; then
    ./compila.sh
fi
avvia
mkdir -p prove
{
    docker exec "$EMULATORE" aws-emulator --version | head -1
    lambda_java
    lettura_da_diecimila
} | tee prove/emulatore.txt
