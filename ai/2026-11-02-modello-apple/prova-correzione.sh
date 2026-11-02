#!/usr/bin/env bash
set -euo pipefail

readonly RIGA_CON_IL_BUG='if (!pagato.equals(f.importo())) {'

if [[ $# -ne 1 ]]; then
  echo 'Uso: ./prova-correzione.sh "<condizione proposta dal modello>"' >&2
  exit 1
fi
readonly CONDIZIONE=$1

cd "$(dirname "$0")"
copia=$(mktemp -d)
trap 'rm -rf "$copia"' EXIT
mkdir "$copia/fm"

RIGA_NUOVA="if ($CONDIZIONE) {" awk -v vecchia="$RIGA_CON_IL_BUG" '
  index($0, vecchia) { match($0, /^ */); print substr($0, 1, RLENGTH) ENVIRON["RIGA_NUOVA"]; next }
  { print }
' fm/ControlloPagamenti.java > "$copia/fm/ControlloPagamenti.java"

if ! grep -F "if ($CONDIZIONE) {" "$copia/fm/ControlloPagamenti.java"; then
  echo "Non trovo la riga con il bug in fm/ControlloPagamenti.java" >&2
  exit 1
fi
java "$copia/fm/ControlloPagamenti.java"
