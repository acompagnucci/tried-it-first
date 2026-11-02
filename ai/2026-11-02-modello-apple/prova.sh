#!/usr/bin/env bash
set -euo pipefail

readonly URL="${MODEL_URL:-http://127.0.0.1:1976}"
readonly DOMANDE_SECCHE=20
readonly PROVE_PROGRAMMA=10
readonly DOMANDA_IT='In Java, new BigDecimal("1.0").equals(new BigDecimal("1.00")) restituisce true o false? Rispondi con una parola.'
readonly DOMANDA_EN='In Java, does new BigDecimal("1.0").equals(new BigDecimal("1.00")) return true or false? Answer with one word.'

cd "$(dirname "$0")"
readonly CARTELLA=prove

chiedi() {
  local domanda=$1
  jq -n --arg d "$domanda" '{model: "system", stream: false, messages: [{role: "user", content: $d}]}' |
    curl -sf "$URL/v1/chat/completions" -H "Content-Type: application/json" -d @-
}

domanda_secca() {
  local nome=$1 domanda=$2
  local testo="$CARTELLA/$nome.txt" grezzo="$CARTELLA/$nome.jsonl"
  local i json risposta
  printf 'Domanda: %s\nRisposta giusta (verificata con jshell): false\n\n' "$domanda" > "$testo"
  : > "$grezzo"
  for ((i = 1; i <= DOMANDE_SECCHE; i++)); do
    if ! json=$(chiedi "$domanda"); then
      echo "fm serve ha smesso di rispondere alla prova $i di $nome." >&2
      exit 1
    fi
    printf '%s\n' "$json" >> "$grezzo"
    risposta=$(jq -r '.choices[0].message.content' <<< "$json")
    printf 'Prova %2d: %s\n' "$i" "$risposta" >> "$testo"
  done
  echo "$testo"
}

if ! curl -sf "$URL/health" > /dev/null; then
  echo "fm serve non risponde su $URL. Lancialo in un altro terminale (README, passo 3)." >&2
  exit 1
fi

mkdir -p "$CARTELLA"
domanda_secca domanda-secca-it "$DOMANDA_IT"
domanda_secca domanda-secca-en "$DOMANDA_EN"

for ((i = 1; i <= PROVE_PROGRAMMA; i++)); do
  uscita="$CARTELLA/programma-$i.txt"
  if java fm/ChiediAlModello.java fm/ControlloPagamenti.java > "$uscita" 2>&1; then
    echo "$uscita"
  else
    echo "$uscita (il programma è uscito con errore, guarda il file)" >&2
  fi
done
