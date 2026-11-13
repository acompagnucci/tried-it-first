#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

ATTESA_MASSIMA=30
SALUTE="https://127.0.0.1:$PORTA/__admin/health"

case "$(uname -m)" in
    arm64 | aarch64) architettura=aarch64 ;;
    *) architettura=x86_64 ;;
esac

if ! docker image inspect "$IMMAGINE" > /dev/null 2>&1; then
    docker build --build-arg ARCHITETTURA="$architettura" -t "$IMMAGINE" emulatore
fi

if [ -n "$(docker ps --quiet --filter "name=^$IMMAGINE$")" ]; then
    echo "L'emulatore è già acceso. Per ripartire da zero: ./ferma-emulatore.sh"
    exit 0
fi

mkdir -p "$HOME/.aws/emulator"
docker run --detach --rm --name "$IMMAGINE" --memory=2g --memory-swap=2g \
    -p "127.0.0.1:$PORTA:$PORTA" \
    -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" \
    "$IMMAGINE" -p "$PORTA" --telemetry off > /dev/null

for _ in $(seq "$ATTESA_MASSIMA"); do
    if curl -sf --cacert "$EMULATORE/ca.pem" "$SALUTE" > /dev/null; then
        docker exec "$IMMAGINE" aws-emulator --version | head -2
        exit 0
    fi
    sleep 1
done
echo "L'emulatore non risponde dopo $ATTESA_MASSIMA secondi: docker logs $IMMAGINE" >&2
exit 1
