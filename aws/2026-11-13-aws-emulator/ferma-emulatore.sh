#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

docker stop "$IMMAGINE" > /dev/null
echo "Emulatore fermato: tutto quello che conteneva è sparito con lui."
