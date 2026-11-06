#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

java_in_docker "$IMMAGINE" mvn -q -B -f carica/pom.xml clean package
rm -rf file
java_in_docker "$IMMAGINE" java -cp carica/target/carica.jar carica.GeneraFile file
wc -l file/*/*.json
