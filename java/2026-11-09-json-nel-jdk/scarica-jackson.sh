#!/bin/sh
set -eu

MAVEN_CENTRAL=https://repo1.maven.org/maven2
JACKSON2=2.23.0
JACKSON3=3.3.0
ANNOTAZIONI=2.23

cd "$(dirname "$0")"

scarica() (
    cartella="lib/$1"
    gruppo="$2"
    artifact="$3"
    versione="$4"
    jar="$artifact-$versione.jar"
    url="$MAVEN_CENTRAL/$gruppo/$artifact/$versione/$jar"
    mkdir -p "$cartella"
    curl -sSfo "$cartella/$jar" "$url"
    echo "$(curl -sSf "$url.sha1")  $cartella/$jar" | shasum -a 1 -c -
)

scarica jackson2 com/fasterxml/jackson/core jackson-databind "$JACKSON2"
scarica jackson2 com/fasterxml/jackson/core jackson-core "$JACKSON2"
scarica jackson2 com/fasterxml/jackson/core jackson-annotations "$ANNOTAZIONI"

scarica jackson3 tools/jackson/core jackson-databind "$JACKSON3"
scarica jackson3 tools/jackson/core jackson-core "$JACKSON3"
scarica jackson3 com/fasterxml/jackson/core jackson-annotations "$ANNOTAZIONI"
