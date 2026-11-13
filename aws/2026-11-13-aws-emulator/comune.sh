#!/bin/sh

STACK=tried-it-first-emulatore
IMMAGINE=aws-emulator
PORTA=9297
EMULATORE="$HOME/.aws/emulator/$PORTA"
export AWS_REGION="${AWS_REGION:-eu-south-1}"
export AWS_PAGER=""

punta_all_emulatore() {
    unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
    unset AWS_SESSION_TOKEN AWS_PROFILE
    export AWS_ENDPOINT_URL="https://127.0.0.1:$PORTA"
    export AWS_SHARED_CREDENTIALS_FILE="$EMULATORE/emulator_credentials"
    export AWS_CONFIG_FILE="$EMULATORE/config"
    export AWS_CA_BUNDLE="$EMULATORE/ca.pem"
    export AWS_EC2_METADATA_DISABLED=true
    export AWS_DISABLE_HOST_PREFIX_INJECTION=true
}

usa() {
    case "${1:-}" in
        emulatore)
            punta_all_emulatore
            ;;
        aws)
            unset AWS_ENDPOINT_URL AWS_CA_BUNDLE AWS_DISABLE_HOST_PREFIX_INJECTION
            ;;
        *)
            echo "Uso: $0 emulatore|aws" >&2
            exit 2
            ;;
    esac
    DOVE=$1
}

account() (
    aws sts get-caller-identity --query Account --output text
)

bucket_del_codice() (
    echo "$STACK-codice-$(account)-$AWS_REGION"
)

solo_errore() (
    sed -n 's/.*(\([^)]*\)) when calling the [A-Za-z]* operation\( ([^)]*)\)\{0,1\}: \(.*\)/\1: \3/p'
)

uscita() (
    aws cloudformation describe-stacks --stack-name "$STACK" \
        --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
)
