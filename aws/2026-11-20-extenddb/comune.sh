#!/bin/sh

export AWS_REGION="${AWS_REGION:-eu-south-1}"
export AWS_PAGER=""

IMMAGINE=maven:3.10.0-amazoncorretto-21
CONTI=tried-it-first-conti
MOVIMENTI=tried-it-first-movimenti
COMPOSE=extenddb/compose.yaml
PORTA=18443
RETE=tried-it-first-extenddb_default
CHIAVE=extenddb/chiave.env
CERTIFICATO=extenddb/certificato.pem
TRUSTSTORE=extenddb/certificato.p12
PASSWORD_TRUSTSTORE=certificato

usa() {
    case "${1:-}" in
        aws)
            unset AWS_ENDPOINT_URL AWS_CA_BUNDLE
            ;;
        extenddb)
            if [ ! -f "$CHIAVE" ]; then
                echo "Manca $CHIAVE: lanciate prima ./avvia-extenddb.sh" >&2
                exit 1
            fi
            unset AWS_PROFILE AWS_SESSION_TOKEN
            . "./$CHIAVE"
            export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
            export AWS_ENDPOINT_URL="https://127.0.0.1:$PORTA"
            export AWS_CA_BUNDLE="$PWD/$CERTIFICATO"
            ;;
        *)
            echo "Uso: $0 aws|extenddb" >&2
            exit 2
            ;;
    esac
    DOVE=$1
    PROVE="prove/$DOVE"
    mkdir -p "$PROVE"
}

nel_container() {
    docker run --rm -v "$PWD:/lavoro" -v "$HOME/.m2:/root/.m2" -w /lavoro "$IMMAGINE" "$@"
}

banca() (
    dove=$1
    classe=$2
    shift 2
    if [ "$dove" = extenddb ]; then
        docker run --rm -v "$PWD:/lavoro" -w /lavoro --network "$RETE" \
            -e AWS_REGION -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY \
            -e AWS_ENDPOINT_URL="https://extenddb:$PORTA" "$IMMAGINE" \
            java -Djavax.net.ssl.trustStore="$TRUSTSTORE" \
            -Djavax.net.ssl.trustStorePassword="$PASSWORD_TRUSTSTORE" \
            -cp banca/target/banca.jar "banca.$classe" "$@"
    else
        eval "$(aws configure export-credentials --format env)"
        docker run --rm -v "$PWD:/lavoro" -w /lavoro \
            -e AWS_REGION -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
            "$IMMAGINE" java -cp banca/target/banca.jar "banca.$classe" "$@"
    fi
)

compose() {
    docker compose -f "$COMPOSE" "$@"
}
