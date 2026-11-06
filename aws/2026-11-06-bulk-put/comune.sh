#!/bin/sh

REGIONE=${AWS_REGION:-eu-south-1}
STACK=tried-it-first-bulk-put
IMMAGINE=maven:3.10.0-amazoncorretto-21

bucket() {
    aws cloudformation describe-stacks --region "$REGIONE" --stack-name "$STACK" \
        --query "Stacks[0].Outputs[?OutputKey=='Bucket'].OutputValue" --output text
}

java_in_docker() {
    docker run --rm -v "$PWD:/lavoro" -v "$HOME/.m2:/root/.m2" -w /lavoro "$@"
}

conta() (
    tabella="tried-it-first-$1"
    if [ $# -eq 1 ]; then
        aws dynamodb scan --region "$REGIONE" --table-name "$tabella" \
            --select COUNT --query Count --output text
    else
        aws dynamodb scan --region "$REGIONE" --table-name "$tabella" \
            --filter-expression '#a = :v' \
            --expression-attribute-names "{\"#a\": \"$2\"}" \
            --expression-attribute-values "{\":v\": $3}" \
            --select COUNT --query Count --output text
    fi
)
