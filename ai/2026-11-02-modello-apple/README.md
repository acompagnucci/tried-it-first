# Il modello AI che c'è già nel Mac, contro lo stesso bug Java

macOS 27 ha un comando che non avevo mai notato: `/usr/bin/fm`. Dà accesso al modello on-device di Apple, quello di Apple Intelligence, dal terminale. Con `fm serve` lo espone anche come API compatibile con OpenAI. Non c'è niente da scaricare a mano e non servono account o chiavi.

Gli ho dato lo stesso bug che avevo dato a Mellum2.1 (`ai/2026-10-12-mellum-locale`) e a Dory (`ai/2026-10-19-dory-giri`): `BigDecimal.equals` che dice che 120 e 120.00 sono diversi.

Qui sotto trovate come rifare la prova e i risultati che ho ottenuto io.

## Contenuto

```
fm/ControlloPagamenti.java   programma con il bug (lo stesso di Mellum e Dory)
fm/ChiediAlModello.java      manda il programma a fm serve e stampa risposta e tempi
prova.sh                     tutte le prove in fila: 20 + 20 domande secche e 10 volte il programma
prova-correzione.sh          mette nel programma la condizione proposta dal modello e lo esegue
prove/                       output del primo giro di prove
prove/secondo-giro/          output del secondo giro
prove/terzo-giro/            output del giro di verifica dello script
```

Niente Maven o Gradle: i file Java si lanciano direttamente con `java`.

## Cosa serve

| | Minimo | Io ho usato |
|---|---|---|
| Sistema | macOS 27 su Apple Silicon, con Apple Intelligence | macOS 27.0.1, Apple M2 Pro |
| RAM | quella che chiede Apple Intelligence | 16 GB |
| Java | JDK 21 | OpenJDK 23 |
| Altro | `curl` e `jq` (ci sono già in macOS 27) | jq 1.7.1 |

Su Linux e Windows non c'è: `fm` è un comando di macOS.

## 1. Java

```sh
java -version
```

Serve la 21 o successiva. Se non ce l'avete: `brew install openjdk@21` (poi seguite le istruzioni che Homebrew stampa per il PATH).

## 2. Licenza e Apple Intelligence

La prima volta `fm` non parte finché non accettate le condizioni d'uso. Vale per tutti gli utenti del Mac, quindi serve `sudo`:

```sh
sudo fm license
```

Poi controllate che il modello sia pronto:

```sh
fm available
```

```
System model available
```

A me la prima volta ha risposto `System model unavailable: modelNotReady`, e in Impostazioni di Sistema non c'era nessuna voce "Apple Intelligence". Il motivo: Siri era in italiano e il Mac in inglese. Apple Intelligence vuole la stessa lingua per tutti e due. Ho messo Siri in English (United States), il Mac ha scaricato quello che gli serviva e dopo qualche minuto `fm available` ha risposto `System model available`.

## 3. Il server

In un terminale a parte:

```sh
fm serve
```

Ascolta su `http://127.0.0.1:1976`, solo in locale. Per controllare:

```sh
curl -s http://127.0.0.1:1976/health
```

```
{"status":"fm serve is running","models":[{"available":true,"name":"system"}]}
```

## 4. Il bug

Dalla cartella di questo README:

```sh
java fm/ControlloPagamenti.java
```

```
Fatture non saldate attese:   [F-003]
Fatture non saldate trovate:  [F-001, F-003]
ERRORE: il risultato non è quello atteso
```

F-001 è da 120.00 e la banca ha registrato 120. Il programma la considera non pagata.

## 5. Lo chiedo al modello

```sh
java fm/ChiediAlModello.java fm/ControlloPagamenti.java
```

La domanda è la stessa dei post su Mellum e Dory:

> Questo programma Java dovrebbe segnalare come non saldata solo la fattura F-003, ma segnala anche F-001. Trova il bug, spiega in poche righe perché succede e proponi la correzione più piccola possibile.

Stampa la risposta, il tempo e i token generati. Non passo né temperatura né altri parametri: il modello usa quelli di default di Apple.

## 6. La domanda secca

```sh
curl -s http://127.0.0.1:1976/v1/chat/completions -H "Content-Type: application/json" \
  -d '{"model":"system","stream":false,"messages":[{"role":"user","content":"In Java, new BigDecimal(\"1.0\").equals(new BigDecimal(\"1.00\")) restituisce true o false? Rispondi con una parola."}]}'
```

Senza `"stream":false` il server risponde a pezzi (righe `data: ...`), mentre l'API di OpenAI, se non glielo chiedi, risponde in un colpo solo.

La stessa domanda si può fare anche senza server:

```sh
fm respond 'In Java, new BigDecimal("1.0").equals(new BigDecimal("1.00")) restituisce true o false? Rispondi con una parola.'
```

La risposta giusta è `false`. Potete verificarlo con `jshell`:

```sh
jshell
jshell> new java.math.BigDecimal("1.0").equals(new java.math.BigDecimal("1.00"))
$1 ==> false
```

## 7. Tutte le prove

Con `fm serve` acceso:

```sh
./prova.sh
```

Fa 20 volte la domanda secca in italiano, 20 volte la stessa in inglese e 10 volte la prova con il programma. Scrive tutto in `prove/` (sovrascrive i miei file). A me ci ha messo un paio di minuti.

## 8. Le correzioni proposte compilano?

Per ogni risposta ho preso la condizione dell'`if` proposta dal modello, l'ho messa al posto di quella con il bug e ho lanciato il programma:

```sh
./prova-correzione.sh 'pagato.compareTo(f.importo()) != 0'
```

```
            if (pagato.compareTo(f.importo()) != 0) {
Fatture non saldate attese:   [F-003]
Fatture non saldate trovate:  [F-003]
OK
```

Lo script lavora su una copia in una cartella temporanea, il file originale non cambia.

## Come è andata a me

Prove del 2 novembre 2026, macOS 27.0.1, modello `system`. Ho lanciato `prova.sh` due volte: il primo giro è in `prove/`, il secondo in `prove/secondo-giro/`.

### Domanda secca

Output in `domanda-secca-it.txt` e `domanda-secca-en.txt`, risposta grezza del server nei `.jsonl`.

| Lingua della domanda | Primo giro | Secondo giro | In tutto |
|---|---|---|---|
| italiano | 0 su 20 | 0 su 20 | 0 su 40, sempre `True` |
| inglese | 9 su 20 | 2 su 20 | 11 su 40 |

Prima di lanciare `prova.sh` avevo fatto la domanda in italiano altre 11 volte a mano, 3 delle quali con `"temperature":0`: sempre `True`.

In italiano non ha mai cambiato idea. In inglese, dopo il primo giro, pensavo che tirasse a indovinare. Il secondo è andato molto peggio: anche con 20 tentativi il caso pesa tanto. Ogni risposta arriva in circa 0,3 secondi.

### Programma + sintomo

Output in `programma-*.txt`, verifiche delle correzioni in `correzioni.txt`.

Primo giro:

| Prova | Cosa dice | La correzione |
|---|---|---|
| 1 | il problema è `equals`, e la correzione è... `equals` | è la stessa riga del bug: ERRORE |
| 2 | `equals` "ignora il segno" | `pagato.compareTo(f.importo()) != 0`: OK |
| 3 | `BigDecimal` non implementa `equals` | `!pagato.compareTo(...) == 0`: non compila |
| 4 | confronta i numeri come stringhe | non compila, e in più cambia i dati del test |
| 5 | parla di "precisione diversa" | `f.numero().compareTo(...)`, una `String` con un `BigDecimal`: non compila |
| 6 | confronta un `BigDecimal` con una stringa | `!pagato.compareTo(...) == 0`: non compila |
| 7 | `compareTo` restituisce -1 | `compareTo(...).equals(0)` su un `int`: non compila |
| 8 | `equals` equivale a `compareTo(...) < 0` | `!pagato.compareTo(...) < 0`: non compila |
| 9 | `equals` "ignora il segno" | `pagato.compareTo(f.importo()) != 0`: OK |
| 10 | `equals` è come `==` | `!pagato.compareTo(...) == 0`: non compila |

Secondo giro: OK la 2 e la 10, le altre 8 non compilano (tra queste un `pagato.notEquals(...)`, metodo che non esiste).

| | Primo giro | Secondo giro | In tutto |
|---|---|---|---|
| Arriva a `compareTo` | 9 | 9 | 18 su 20 |
| La correzione compila e il programma stampa OK | 2 | 2 | 4 su 20 |
| Non compila | 7 | 8 | 15 su 20 |
| Dice che `equals` guarda anche la scala | 0 | 0 | 0 su 20 |
| Cambia il risultato atteso del test | 1 | 4 | 5 su 20 |

Arrivare a `compareTo` me lo aspettavo: è la correzione che si trova ovunque. Però funziona solo 4 volte su 20, e il motivo vero non c'è mai. Secondo il modello `equals` ignora il segno, confronta stringhe, è come `==`, oppure il problema è un `double` che nel programma non c'è.

Il test cambiato merita due righe. In 5 risposte il modello riscrive tutto il programma e mette come risultato atteso `[F-001, F-003]`, cioè proprio l'output sbagliato che il programma stampa per colpa del bug. Nella prova 4 del primo giro cambia anche il pagamento di F-001 da `120` a `120.00`, nella prova 7 del secondo lo fa diventare `-120`. Le condizioni che propone in quelle risposte non compilano comunque.

Il `!pagato.compareTo(...) == 0` che non compila l'aveva proposto anche Mellum, una volta su dieci. Qui è uscito 8 volte su 20.

Tempi tra 4 e 15 secondi, tra 115 e 749 token generati. Il programma più la domanda sono 641 token (`fm count-tokens`).

### Il terzo giro

Dopo aver sistemato la gestione degli errori in `prova.sh` l'ho rilanciato per controllare che funzionasse ancora, e ho tenuto anche questo giro in `prove/terzo-giro/`. Non è nelle tabelle sopra, ma i numeri sono di quel genere: domanda secca in italiano 0 su 20, in inglese 6 su 20. Col programma 3 correzioni su 10 stampano OK, 6 non compilano e una lascia il bug com'era. Delle 3 che passano, una è `pagato.compareTo(f.importo()) < 0`: il test passa, ma una fattura pagata più del dovuto non verrebbe segnalata. Il risultato atteso del test cambia 2 volte, la scala non compare mai.

### Con Mellum e Dory

Stesso bug, stessa domanda. Le prove non sono le stesse in numero e parametri, quindi è un confronto a occhio:

| | Mellum2.1 | Dory, 1 giro | Modello Apple |
|---|---|---|---|
| Da installare | llama.cpp + 8 GB | llama.cpp da compilare + 3,7 GB | niente |
| Domanda secca (italiano) | 10 su 28 | 2 su 4 | 0 su 40 |
| Programma: corregge il bug | 7 su 10 | 2 su 2 | 4 su 20, motivo giusto 0 su 20 |
| Tempo per la prova col programma, quando risponde | 20-70 s | 10-13 min | 4-15 s |

È di gran lunga il più veloce e l'unico da non installare. Sul mio bug, però, è quello che sbaglia di più.

## Domande che mi sono fatto

**E il modello di Private Cloud Compute, quello più grande?**
Avevo letto che `fm` permette di sceglierlo con `--model pcc`. Sul mio Mac no: `fm --help` mostra solo `system`, e `--model pcc` risponde `The value 'pcc' is invalid for '--model <model>'. Please provide one of 'system'.`. Non so cosa serva per abilitarlo, non l'ho provato.

**Il codice esce dal Mac?**
Apple dice che il modello `system` gira sul dispositivo, e `fm serve` ascolta solo su `127.0.0.1`. Non ho controllato il traffico di rete durante le prove.

**Perché in inglese va meglio?**
Non lo so. Ho solo visto che alla stessa domanda in italiano risponde `True` 40 volte su 40, in inglese 29 su 40. Anche con Dory, che è un modello solo inglese, la lingua sembrava pesare.

**Allora a cosa serve?**
Apple lo indica per estrarre informazioni e riassumere testi dentro le app, e dice che, viste le dimensioni, non è pensato per una chat che sappia un po' di tutto. Io ho provato solo questo bug, quindi sul resto non posso dire niente. Le regole di Java non le chiederei a lui, e una correzione proposta la compilerei sempre prima di fidarmi.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `YOU HAVE NOT AGREED TO THE APPLE FOUNDATION MODELS CLI LEGAL NOTICE & TERMS` | `sudo fm license` (passo 2). |
| `System model unavailable: modelNotReady` | Apple Intelligence non è attiva o sta ancora scaricando. Se in Impostazioni non c'è la voce Apple Intelligence, controllate che Siri e il Mac abbiano la stessa lingua. |
| curl risponde con tante righe `data: {...}` | Manca `"stream":false` nel JSON. |
| `fm serve non risponde su http://127.0.0.1:1976` | Il server non è acceso, o è su un'altra porta: `MODEL_URL=http://127.0.0.1:9000 ./prova.sh` |
| `error: class found on application class path` | Ci sono file `.class` compilati nella cartella. Cancellateli. |

## Pulizia

Ctrl+C nel terminale di `fm serve`. Non c'è niente da disinstallare. Se avete cambiato la lingua di Siri per attivare Apple Intelligence, potete rimetterla com'era, ma probabilmente il modello torna non disponibile.

## Link

- Foundation Models: https://developer.apple.com/documentation/foundationmodels
- Il post su Mellum con lo stesso bug: `ai/2026-10-12-mellum-locale`
- Il post su Dory con lo stesso bug: `ai/2026-10-19-dory-giri`
- `BigDecimal.equals`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html#equals(java.lang.Object)
