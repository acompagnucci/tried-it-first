#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

nel_container mvn -q -f banca/pom.xml package
