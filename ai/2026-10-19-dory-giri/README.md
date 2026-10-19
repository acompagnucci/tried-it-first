# Un modello AI con la manopola: Dory a 1, 2 e 4 giri

Il 16 ottobre NVIDIA ha messo su Hugging Face Nemotron Labs Dory 1.0, un modello da 4 miliardi di parametri. Ha una cosa che non avevo mai visto: un blocco di 20 layer che si può far girare 1, 2, 3 o 4 volte sullo stesso token. Più giri, più calcolo, e secondo NVIDIA risposte migliori. Il numero di giri si sceglie quando si avvia il server, il file del modello è sempre lo stesso.

Gli ho dato lo stesso bug che avevo dato a Mellum2.1 la settimana scorsa (`ai/2026-10-12-mellum-locale`): `BigDecimal.equals` che dice che 120 e 120.00 sono diversi. Volevo vedere se girare la manopola cambia qualcosa.

Qui sotto trovate come rifare la prova e i risultati che ho ottenuto io.

## Contenuto

```
dory/ControlloPagamenti.java   programma con il bug (lo stesso del post su Mellum)
dory/ChiediAlModello.java      manda il programma al modello e stampa risposta e tempi
prove/giri-1/                  output con 1 giro
prove/giri-2/                  output con 2 giri
prove/giri-4/                  output con 4 giri
```

Niente Maven o Gradle: i file si lanciano direttamente con `java`.

## Cosa serve

| | Minimo | Io ho usato |
|---|---|---|
| Sistema | macOS su Apple Silicon | macOS 27, Apple M2 Pro |
| RAM | 16 GB | 16 GB |
| Disco libero | 6 GB | |
| Java | JDK 21 | OpenJDK 23 |
| Strumenti per compilare | Command Line Tools di Xcode, cmake, openssl@3 | cmake 4.4.4, openssl 3.6.3 |

Il supporto per Dory non è ancora nella versione ufficiale di llama.cpp, quindi `brew install llama.cpp` non basta: va compilato da una pull request aperta (ggml-org/llama.cpp#30306). I comandi sono quelli della model card di NVIDIA, pensati per Mac con Apple Silicon. Su Linux e Windows non ci ho provato.

## 1. Java

```sh
java -version
```

Serve la 21 o successiva. Se non ce l'avete: `brew install openjdk@21` (poi seguite le istruzioni che Homebrew stampa per il PATH).

## 2. llama.cpp con il supporto per Dory

```sh
xcode-select --install          # se non li avete già
brew install cmake openssl@3

git init llama.cpp-dory
cd llama.cpp-dory
git remote add origin https://github.com/brianchmielNV/llama.cpp.git
git fetch --depth 1 origin 98a74c97ea09ac72f6ceda1559808fc0a2f447ce
git checkout --detach FETCH_HEAD

HF_UI_VERSION=b11551 cmake -S . -B build \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_SYSROOT="/Library/Developer/CommandLineTools/SDKs/MacOSX26.sdk" \
    -DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON \
    -DGGML_BLAS=ON -DGGML_BLAS_VENDOR=Apple \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=ON \
    -DLLAMA_OPENSSL=ON -DOPENSSL_ROOT_DIR="$(brew --prefix openssl@3)"
cmake --build build --parallel 4 --target llama-server
```

Il commit `98a74c9` è quello indicato da NVIDIA, così non dipendete da cosa cambia nella pull request. A me la compilazione ha preso poco più di un minuto.

Se cmake non trova `MacOSX26.sdk`, guardate quali avete con `ls /Library/Developer/CommandLineTools/SDKs/` e mettete quello.

Verifica:

```sh
./build/bin/llama-server --version
```

```
version: 0.6.0-dev (build 1, commit 98a74c9)
```

## 3. Il modello

Il file GGUF ufficiale di NVIDIA, Q5_K_M, 3,7 GB:

```sh
mkdir -p ~/models
curl -L -C - -o ~/models/NVIDIA-Nemotron-Labs-Dory1.0-4B-Q5_K_M.gguf \
  "https://huggingface.co/nvidia/NVIDIA-Nemotron-Labs-Dory1.0-4B-GGUF/resolve/main/NVIDIA-Nemotron-Labs-Dory1.0-4B-Q5_K_M.gguf"

echo "96080be49ffd67b916018b7bdef8c484762576fb52780e10ab98ac4978a660e0  $HOME/models/NVIDIA-Nemotron-Labs-Dory1.0-4B-Q5_K_M.gguf" | shasum -a 256 -c -
```

Deve stampare `OK`.

## 4. Avvio del server

Dalla cartella `llama.cpp-dory`, con 2 giri:

```sh
./build/bin/llama-server -m ~/models/NVIDIA-Nemotron-Labs-Dory1.0-4B-Q5_K_M.gguf \
  --alias dory --host 127.0.0.1 --port 8080 -ngl 0 \
  --ctx-size 16384 --parallel 1 --batch-size 512 --ubatch-size 512 \
  --cache-type-k f16 --cache-type-v f16 --flash-attn off --no-context-shift \
  --jinja --reasoning on \
  --temp 1.0 --top-p 0.95 --top-k 0 --min-p 0 --repeat-penalty 1 \
  --override-kv "dory.num_loops=int:2,dory.recurrent_kv_cache_mode=str:per_loop"
```

Il numero di giri è il `2` in `dory.num_loops=int:2`. Per provare 1 o 4 giri fermate il server con Ctrl+C e rilanciatelo cambiando solo quello.

Rispetto al comando della model card ho cambiato due cose:

- **`-ngl 0`**, cioè niente GPU. La model card usa `-ngl 999`, ma sul mio Mac con la GPU va più piano che senza (vedi sotto). Nella pull request c'è scritto "Metal untested", e si vede.
- **`--ctx-size 16384`** invece di 4096: il ragionamento del modello più il programma Java non ci stavano.

Temperatura e top-p sono quelli consigliati da NVIDIA. Quando compare `listening on http://127.0.0.1:8080` è pronto. Da qui in poi usate un secondo terminale.

## 5. Il bug

Dalla cartella di questo README:

```sh
java dory/ControlloPagamenti.java
```

```
Fatture non saldate attese:   [F-003]
Fatture non saldate trovate:  [F-001, F-003]
ERRORE: il risultato non è quello atteso
```

F-001 è da 120.00 e la banca ha registrato 120. Il programma la considera non pagata.

## 6. Lo chiedo al modello

```sh
java dory/ChiediAlModello.java dory/ControlloPagamenti.java
```

La domanda che parte è la stessa del post su Mellum:

> Questo programma Java dovrebbe segnalare come non saldata solo la fattura F-003, ma segnala anche F-001. Trova il bug, spiega in poche righe perché succede e proponi la correzione più piccola possibile.

Stampa il ragionamento, la risposta, il tempo e la velocità. Se il modello non risponde entro 15 minuti il programma si ferma con `HttpTimeoutException`.

## 7. La domanda secca

```sh
curl -s http://localhost:8080/v1/chat/completions -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"In Java, new BigDecimal(\"1.0\").equals(new BigDecimal(\"1.00\")) restituisce true o false? Rispondi con una parola."}],"temperature":1.0,"top_p":0.95}'
```

La risposta giusta è `false`. Potete verificarlo con `jshell`:

```sh
jshell
jshell> new java.math.BigDecimal("1.0").equals(new java.math.BigDecimal("1.00"))
$1 ==> false
```

## Come è andata a me

Per ogni numero di giri (1, 2 e 4) ho riavviato il server, ho fatto 4 volte la domanda secca e 2 volte la prova con il programma. In tutto 18 prove, tutte sulla CPU.

### Velocità: GPU contro CPU

Prima delle prove ho misurato la velocità su una frase breve (48 token generati):

| Giri | GPU (`-ngl 999`) | Solo CPU (`-ngl 0`) |
|---|---|---|
| 1 | 8,1 token/s | 13,1 token/s |
| 2 | 5,3 token/s | 11,0 token/s |
| 4 | 3,1 token/s | 8,4 token/s |

Con risposte lunghe la CPU scende un po': circa 12 token/s a 1 giro, 9-10 a 2 giri, 7 a 4 giri. Per confronto, Mellum2.1 sullo stesso Mac con llama.cpp ufficiale faceva 56-64 token/s.

### Domanda secca

Output completi, ragionamento compreso, in `prove/giri-*/domanda-secca.txt` (e la risposta grezza del server in `domanda-secca.jsonl`).

| Giri | Risposte | Giuste | Token in media | Tempo in media |
|---|---|---|---|---|
| 1 | false, true, false, true | 2 su 4 | 4.254 | 6 min |
| 2 | false, true, false, true | 2 su 4 | 1.651 | 3 min |
| 4 | true, true, true, true | 0 su 4 | 2.192 | 5 min |

Con 2 giri il modello scrive meno della metà dei token e risponde prima, come dice la model card. Che risponda giusto più spesso, invece, non si vede.

Nella prova 4 a 4 giri il ragionamento cita la documentazione:

> From Java docs: "Returns true if and only if this BigDecimal represents the same value as the specified BigDecimal argument."

Una frase quasi uguale nella Javadoc di `BigDecimal` c'è, ma spiega cosa significa la notazione `(i == j)` usata nelle descrizioni dei metodi. Non parla di `equals`. Su `equals` la Javadoc dice: "this method considers two BigDecimal objects equal only if they are equal in value and scale. Therefore 2.0 is not equal to 2.00".

### Programma + sintomo

Output completi in `prove/giri-*/prova-*.txt`.

| Giri | Prova 1 | Prova 2 |
|---|---|---|
| 1 | trova il bug, propone `compareTo` (588 s, 6.702 token) | trova il bug, propone `compareTo` (780 s, 8.983 token) |
| 2 | trova il bug, propone `compareTo` (764 s, 7.044 token) | fermato a 15 minuti dopo 8.537 token |
| 4 | fermato a 15 minuti dopo 5.775 token | fermato a 15 minuti dopo 5.927 token |

Quando finisce, la risposta è giusta e in un italiano pulito. Il problema è il tempo: gli servono 7.000-9.000 token di ragionamento, e a 4 giri ne fa meno di 7 al secondo. Con il programma davanti, più giri non gli fanno scrivere meno.

I 15 minuti sono il timeout di `ChiediAlModello.java`, lo stesso del post su Mellum. Quando scatta, il server interrompe la richiesta e non restituisce il ragionamento: il numero di token l'ho preso dal log del server. Non so se si sarebbe fermato da solo o se era in un loop come quelli di Mellum.

### Altre cose che ho notato

Il ragionamento è sempre in inglese. In una delle prove a 4 giri si chiede se l'utente stia scrivendo in inglese, visto che la domanda contiene "restituisce". La domanda è tutta in italiano, e la model card dice che il modello è solo inglese: anche questo può pesare.

## Domande che mi sono fatto

**Perché le prove solo sulla CPU?**
Perché sul mio Mac con Metal va più piano a ogni numero di giri (tabella sopra). La pull request lo dice: "Metal untested". Su una GPU NVIDIA, dove il supporto è testato, i numeri possono essere diversi. Non l'ho provato.

**Mi fido dei benchmark di NVIDIA?**
Sono loro, fatti con il modello in BF16 (io ho usato il Q5_K_M), e al momento nessuno li ha rifatti. Le mie sono 18 prove su un solo bug: raccontano come è andata a me, per una statistica ne servirebbero molte di più.

**E con 3 giri?**
Si può (`dory.num_loops=int:3`), ma non l'ho provato.

**`per_loop` o `last_loop`?**
Ho usato `per_loop`, quello del comando della model card. Con `last_loop` la cache occupa meno memoria, ma secondo la pull request le risposte cambiano. Non l'ho provato.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `unknown model architecture: 'dory'` | State usando il llama.cpp di Homebrew. Va usato quello compilato al passo 2: `./build/bin/llama-server`. |
| cmake non trova l'SDK | `ls /Library/Developer/CommandLineTools/SDKs/` e mettete in `-DCMAKE_OSX_SYSROOT` uno di quelli che avete. |
| `HttpTimeoutException: request timed out` | Il modello non ha finito in 15 minuti. È successo anche a me, tre volte su sei. |
| `Non riesco a collegarmi a http://localhost:8080` | Il server non è partito, o è su un'altra porta: `MODEL_URL=http://localhost:9000 java dory/ChiediAlModello.java dory/ControlloPagamenti.java` |
| `error: class found on application class path` | Ci sono file `.class` compilati nella cartella. Cancellateli. |

## Pulizia

```sh
# Ctrl+C nel terminale del server, poi
rm ~/models/NVIDIA-Nemotron-Labs-Dory1.0-4B-Q5_K_M.gguf
rm -rf llama.cpp-dory
brew uninstall cmake            # solo se non vi serve per altro
```

## Link

- Model card: https://huggingface.co/nvidia/NVIDIA-Nemotron-Labs-Dory1.0-4B-GGUF
- Pull request di llama.cpp: https://github.com/ggml-org/llama.cpp/pull/30306
- Il post su Mellum con lo stesso bug: `ai/2026-10-12-mellum-locale`
- `BigDecimal.equals`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html#equals(java.lang.Object)
