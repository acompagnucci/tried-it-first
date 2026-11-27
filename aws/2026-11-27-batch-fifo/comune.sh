#!/bin/sh

export AWS_REGION="${AWS_REGION:-eu-south-1}"
export AWS_PAGER=""

STACK=tried-it-first-batch-fifo
IMMAGINE=maven:3.10.0-amazoncorretto-21
CONTI=tried-it-first-batch-conti
INVOCAZIONI=tried-it-first-batch-invocazioni
FALLITI=tried-it-first-falliti.fifo

account() (
    aws sts get-caller-identity --query Account --output text
)

bucket_del_codice() (
    echo "$STACK-codice-$(account)-$AWS_REGION"
)

nel_container() {
    docker run --rm -v "$PWD:/lavoro" -v "$HOME/.m2:/root/.m2" -w /lavoro "$IMMAGINE" "$@"
}

bonifici() (
    classe=$1
    shift
    eval "$(aws configure export-credentials --format env)"
    docker run --rm -v "$PWD:/lavoro" -w /lavoro \
        -e AWS_REGION -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
        "$IMMAGINE" java -cp bonifici/target/bonifici.jar "bonifici.$classe" "$@"
)

url_della_coda() (
    aws sqs get-queue-url --queue-name "$1" --query QueueUrl --output text
)
