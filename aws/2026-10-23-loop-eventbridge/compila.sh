#!/bin/sh
set -eu

cd "$(dirname "$0")"
mkdir -p pacchetti

for versione in 2.17.135 2.21.23 2.21.24 2.46.7; do
    docker run --rm -v "$PWD/giro:/giro" -v "$HOME/.m2:/root/.m2" -w /giro \
        maven:3.10.0-amazoncorretto-21 mvn -q -B clean package -Dversione.sdk="$versione"
    cp "giro/target/giro-$versione.jar" pacchetti/
done
ls -l pacchetti
