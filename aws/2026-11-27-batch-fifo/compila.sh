#!/bin/sh
set -eu

cd "$(dirname "$0")"
. ./comune.sh

nel_container mvn -q -B -f bonifici/pom.xml clean package
