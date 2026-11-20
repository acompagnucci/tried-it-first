#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

compose down --volumes
rm -f "$CHIAVE" "$CERTIFICATO" "$TRUSTSTORE"
