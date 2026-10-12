# Mellum2.1 in locale contro un bug Java

L'8 ottobre JetBrains ha rilasciato Mellum2.1, un modello open (Apache 2.0) per agenti che scrivono codice. Ha 12 miliardi di parametri ma ne attiva circa 2,5 per token, quindi gira su un portatile.

L'ho installato sul mio Mac e gli ho dato un bug che ho visto più di una volta su codice che gestisce importi: `BigDecimal.equals` che dice che 120 e 120.00 sono diversi.

Qui sotto trovate come rifare la prova e i risultati che ho ottenuto io.

## Contenuto

```
mellum/ControlloPagamenti.java   programma con il bug
mellum/ChiediAlModello.java      manda il programma al modello e stampa risposta e tempi
prove/                           output completi delle mie prove (contesto 16k e 131k)
```

Niente Maven o Gradle: i file si lanciano direttamente con `java`.

## Cosa serve

| | Minimo | Io ho usato |
|---|---|---|
| Sistema | macOS, Linux o Windows 10/11 | macOS 27, Apple M2 Pro |
| RAM | 16 GB | 16 GB |
| Disco libero | 10 GB | |
| Java | JDK 21 | OpenJDK 23 |
| llama.cpp | versione recente | 0.6.0 (build 11429) |

Con 8 GB di RAM non ci provate, il modello da solo ne occupa circa 8.

Ho provato tutto su macOS. I comandi per Linux e Windows sono quelli indicati dai rispettivi pacchetti, ma non li ho eseguiti: se qualcosa non torna, apritemi una issue.

## 1. Java

```sh
java -version
```

Serve la 21 o successiva. Se non ce l'avete:

- macOS: `brew install openjdk@21` (poi seguite le istruzioni che Homebrew stampa per il PATH)
- Ubuntu/Debian: `sudo apt install openjdk-21-jdk`
- Windows: `winget install EclipseAdoptium.Temurin.21.JDK` e riaprite il terminale

## 2. llama.cpp

È il programma che carica il modello e lo espone come server sulla vostra macchina.

- macOS e Linux: `brew install llama.cpp`
- Windows: `winget install -e --id ggml.llamacpp` e riaprite il terminale
- oppure scaricate il pacchetto da https://github.com/ggml-org/llama.cpp/releases

Verifica:

```sh
llama-server --version
```

## 3. Il modello

Uso il file GGUF ufficiale di JetBrains in versione `Q4_K_M` (8,1 GB), quella che loro stessi consigliano.

macOS e Linux:

```sh
mkdir -p ~/models
curl -L -C - -o ~/models/Mellum2.1-12B-A2.5B-Thinking-Q4_K_M.gguf \
  "https://huggingface.co/JetBrains/Mellum2.1-12B-A2.5B-Thinking-GGUF/resolve/main/Mellum2.1-12B-A2.5B-Thinking-Q4_K_M.gguf"
```

Windows (PowerShell):

```powershell
mkdir $HOME\models -Force
curl.exe -L -C - -o $HOME\models\Mellum2.1-12B-A2.5B-Thinking-Q4_K_M.gguf "https://huggingface.co/JetBrains/Mellum2.1-12B-A2.5B-Thinking-GGUF/resolve/main/Mellum2.1-12B-A2.5B-Thinking-Q4_K_M.gguf"
```

A me ci ha messo una ventina di minuti. Se si interrompe, rilanciate lo stesso comando e riparte da dove era arrivato.

Il file deve pesare esattamente 8.071.295.264 byte (`ls -l ~/models`).

## 4. Avvio del server

```sh
llama-server -m ~/models/Mellum2.1-12B-A2.5B-Thinking-Q4_K_M.gguf \
  --ctx-size 16384 --temp 0.6 --top-p 0.95 --top-k 20
```

Su Windows mettete tutto su una riga e usate `$HOME\models\...` al posto di `~/models/...`.

Temperatura e top-p/top-k sono quelli della model card di JetBrains. Il contesto l'ho tenuto a 16.384 token invece dei 131.072 supportati. Ho provato anche con il contesto pieno (`--ctx-size 131072 --parallel 1`): sul mio Mac da 16 GB parte, ma i risultati non sono cambiati in modo chiaro (vedi sotto).

Quando compare `listening on http://127.0.0.1:8080` è pronto. Da qui in poi usate un secondo terminale.

## 5. Il bug

Dalla cartella di questo README:

```sh
java mellum/ControlloPagamenti.java
```

```
Fatture non saldate attese:   [F-003]
Fatture non saldate trovate:  [F-001, F-003]
ERRORE: il risultato non è quello atteso
```

F-001 è da 120.00 e la banca ha registrato 120. Il programma la considera non pagata.

## 6. Lo chiedo al modello

```sh
java mellum/ChiediAlModello.java mellum/ControlloPagamenti.java
```

La domanda che parte è questa:

> Questo programma Java dovrebbe segnalare come non saldata solo la fattura F-003, ma segnala anche F-001. Trova il bug, spiega in poche righe perché succede e proponi la correzione più piccola possibile.

Stampa il ragionamento, la risposta, il tempo e la velocità. La temperatura non è zero, quindi ogni volta la risposta cambia un po'.

## 7. La domanda secca

Poi ho provato a chiedergli la regola senza codice:

```sh
curl -s http://localhost:8080/v1/chat/completions -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"In Java, new BigDecimal(\"1.0\").equals(new BigDecimal(\"1.00\")) restituisce true o false? Rispondi con una parola."}],"temperature":0.6,"top_p":0.95,"top_k":20}'
```

La risposta giusta è `false`. Potete verificarlo con `jshell`:

```sh
jshell
jshell> new java.math.BigDecimal("1.0").equals(new java.math.BigDecimal("1.00"))
$1 ==> false
```

## Come è andata a me

### Programma + sintomo

Dieci prove: cinque con contesto a 16.384 token, cinque con il contesto pieno da 131.072 (`--ctx-size 131072 --parallel 1`). Output completi in `prove/contesto-16k/` e `prove/contesto-131k/`.

| Prova | 16k | 131k |
|---|---|---|
| 1 | trova il bug, propone `compareTo` (19,9 s) | trova il bug, propone `compareTo` (65,6 s) |
| 2 | trova il bug, ma il codice proposto (`!pagato.compareTo(...) == 0`) non compila (38,4 s) | trova il bug, propone `compareTo` (47,2 s) |
| 3 | nessuna risposta: si convince che 120 e 120.00 siano `equals`, gira in tondo e finisce il contesto (276,9 s) | stesso loop: l'ho fermato io dopo 7 minuti e 24.711 token |
| 4 | trova il bug, propone `compareTo` (36,8 s) | trova il bug, propone `compareTo` (27,8 s) |
| 5 | trova il bug, metà risposta in inglese (69,9 s) | trova il bug, propone `compareTo` (31,2 s) |

In tutto 7 su 10 corrette, una con codice che non compila, due in loop. Velocità tra 56 e 64 token/s.

### Domanda secca

28 volte la stessa domanda del punto 7, in quattro serie da sette. Dettagli in `prove/domanda-secca.txt`.

| Serie | Contesto | Giuste |
|---|---|---|
| A | 16k, mentre il server lavorava anche su altre richieste | 1 su 7 |
| B | 16k, una richiesta alla volta | 2 su 7 |
| C | 131k | 6 su 7 |
| D | 131k, subito dopo la C | 1 su 7 |

In tutto **10 giuste su 28**. Dopo la serie C ero convinto che il problema fosse il contesto troppo piccolo. La serie D mi ha fatto cambiare idea: con sette tentativi il caso pesa più di quanto pensassi.

### Altre cose che ho notato

Il ragionamento è sempre in inglese anche se la domanda è in italiano, e nelle risposte in italiano ci sono parole storpiate ("inattualibrate", "condiione").

Una cosa che mi ha fatto sorridere: SonarQube segnala questo bug da solo (regola `java:S9351`) appena apri il file. Gratis e senza scaricare 8 GB.

## La correzione

`BigDecimal.equals` confronta valore e scala. `120` ha scala 0, `120.00` ha scala 2, quindi per `equals` sono diversi. Per confrontare solo il valore si usa `compareTo`:

```diff
-            if (!pagato.equals(f.importo())) {
+            if (pagato.compareTo(f.importo()) != 0) {
```

Dopo la modifica `java mellum/ControlloPagamenti.java` stampa `OK`.

## Altri modi per parlarci

Il server di llama.cpp espone un'API compatibile con quella di OpenAI (`/v1/chat/completions`), quindi:

- **Browser**: aprite http://localhost:8080, c'è una chat già pronta. Provato.
- **curl** o qualsiasi client HTTP: come al punto 7. Provato.
- **Java**: `ChiediAlModello.java` usa solo `java.net.http`. In un progetto vero userei Spring AI o LangChain4j configurati su un endpoint OpenAI-compatible con `http://localhost:8080/v1` come base URL. Non l'ho ancora provato con questo modello.
- **Docker**: la model card indica `docker model run hf.co/JetBrains/Mellum2.1-12B-A2.5B-Thinking`. Non provato.

## Se volessi usarlo davvero per sviluppare

Le domande che mi sono fatto, alcune da chi parte da zero, altre da chi deve decidere se metterlo in un team.

**Mi serve una GPU?**
No. Sul Mac usa la GPU integrata tramite Metal. Su un PC senza scheda video gira sulla CPU, più lento.

**Il mio codice esce dalla macchina?**
No. Il server ascolta su `127.0.0.1`, quindi risponde solo in locale. Se lo esponete in rete con `--host 0.0.0.0`, mettete almeno `--api-key`, perché altrimenti chiunque nella rete può usarlo.

**Quanto costa?**
Niente a token. Costa la macchina, la corrente e il tempo che ci mettete.

**Posso usarlo in azienda?**
La licenza Apache 2.0 lo permette, anche per uso commerciale. Ma prima chiedete a chi decide le policy: un modello locale resta un software scaricato da internet che legge il vostro codice.

**Mi fido dei benchmark?**
Sono dichiarati da JetBrains (47% su SWE-bench Verified) e al momento nessuno li ha rifatti in modo indipendente. Le mie sono 38 prove su un solo bug: raccontano come è andata a me, per una statistica ne servirebbero molte di più.

**Perché trova il bug ma sbaglia la domanda secca?**
La mia ipotesi è che con il codice e il sintomo davanti ragioni sul caso concreto, mentre sulla domanda secca risponda di getto. Io adesso gli passo sempre il codice, e le regole del linguaggio le controllo nella documentazione.

**Con più contesto va meglio?**
Me lo sono chiesto anche io dopo il primo giro di prove. Ho rifatto tutto con 131.072 token: la domanda secca è passata da 3 su 14 a 7 su 14, ma con una serie da 6 su 7 seguita da una da 1 su 7. Il loop invece c'è con tutti e due: con più contesto dura solo di più. Per questo nel README lascio 16.384, che almeno il loop lo ferma prima.

**Lo metterei in un team?**
Un Mac a testa regge. Per servire più persone da un server la model card dà i comandi per vLLM su GPU, ma è un altro lavoro e non l'ho fatto.

**E con l'IDE?**
È la cosa che voglio provare dopo: usarlo come modello locale dentro IntelliJ e VS Code su un progetto vero. Ne parlo in un post a parte quando l'ho fatto.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `llama-server: command not found` | Il passo 2 non è andato, oppure va riaperto il terminale. |
| Il server si chiude con un errore di memoria | Chiudete le app pesanti o abbassate il contesto: `--ctx-size 8192`. |
| `Non riesco a collegarmi a http://localhost:8080` | Il server non è partito, o è su un'altra porta: `MODEL_URL=http://localhost:9000 java mellum/ChiediAlModello.java mellum/ControlloPagamenti.java` |
| `Il modello ha esaurito il contesto prima di finire` | È successo anche a me (prova 3). Rilanciate, oppure aumentate `--ctx-size`. |
| La prima risposta è lenta | Il modello si sta caricando in memoria. Dalla seconda va meglio. |
| `error: class found on application class path` | Ci sono file `.class` compilati nella cartella. Cancellateli. |

## Pulizia

```sh
# Ctrl+C nel terminale del server, poi
rm ~/models/Mellum2.1-12B-A2.5B-Thinking-Q4_K_M.gguf
brew uninstall llama.cpp        # Windows: winget uninstall -e --id ggml.llamacpp
```

## Link

- Model card: https://huggingface.co/JetBrains/Mellum2.1-12B-A2.5B-Thinking
- File GGUF: https://huggingface.co/JetBrains/Mellum2.1-12B-A2.5B-Thinking-GGUF
- llama.cpp: https://github.com/ggml-org/llama.cpp
- `BigDecimal.equals`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html#equals(java.lang.Object)
