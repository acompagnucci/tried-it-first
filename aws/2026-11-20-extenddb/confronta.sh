#!/bin/sh
set -u

cd "$(dirname "$0")" || exit

for file in 01-bonifici.txt 02-stream.txt; do
    echo "== $file"
    if diff -u --label aws --label extenddb "prove/aws/$file" "prove/extenddb/$file"; then
        echo "uguali"
    fi
    echo
done > prove/confronto.txt
cat prove/confronto.txt
