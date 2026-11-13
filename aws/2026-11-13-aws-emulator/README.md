# Il giro di un bonifico su AWS Emulator e su AWS

AWS Emulator è uscito l'11 novembre 2026: un programma che risponde alle API di AWS sulla vostra macchina, senza account. La CLI e gli SDK ci arrivano cambiando qualche variabile d'ambiente, il codice resta quello.

Per provarlo ho scritto una piccola app serverless e l'ho fatta girare due volte, senza cambiare una riga: sull'emulatore, in Docker sul mio Mac, e su AWS a Milano. L'app riceve un bonifico da un'API, lo mette in coda, lo esegue in DynamoDB, scrive la contabile su S3 e manda una notifica con EventBridge.

```
curl POST /bonifici
  → API Gateway → Lambda ricevi → coda SQS FIFO Bonifici
      → Lambda esegui → transazione su DynamoDB (tabella Conti)
                      → contabile su S3 → Lambda archivia → tabella Contabili
                      → evento su EventBridge → regola → coda Notifiche
      dopo 3 tentativi falliti → coda Scartati
```

Le Lambda sono in Node.js 24, il template è SAM.

## Cosa serve

Ho provato su un Mac M2 Pro con 16 GB di RAM e macOS 27.2.1, con:

- Docker Desktop 29.9.3 (l'immagine dell'emulatore pesa 1,66 GB e il container ha un limite di 2 GB di memoria)
- AWS CLI 2.39.5
- curl 8.7.1 e jq 1.7.1
- per la parte su AWS: un account dove potete creare stack con ruoli IAM; io ho usato Milano, `eu-south-1`
- solo per `lambda-java.sh`: un JDK 21 o più nuovo (io avevo il 23, compila con `--release 21`)

L'emulatore che ho usato è la versione 1.0.338 del 12 novembre 2026. Ne escono più versioni al giorno: con una più nuova alcuni risultati qui sotto potrebbero già essere diversi.

## I file

| File | Cosa fa |
|---|---|
| `emulatore/Dockerfile` | il Dockerfile della documentazione di AWS, con l'architettura come argomento |
| `avvia-emulatore.sh` | costruisce l'immagine se manca e avvia l'emulatore sulla porta 9297, con la telemetria spenta |
| `ferma-emulatore.sh` | ferma il container; quello che c'era dentro sparisce |
| `comune.sh` | nome dello stack, e le funzioni che puntano la CLI all'emulatore o ad AWS |
| `template.yaml` | tabelle, code, regola EventBridge, bucket, API e le tre Lambda |
| `lambda/` | `ricevi.mjs`, `esegui.mjs`, `archivia.mjs` |
| `deploy.sh` | crea il bucket per il codice, impacchetta e fa il deploy dello stack |
| `prova.sh` | il giro del bonifico; scrive i risultati in `prove/emulatore` o `prove/aws` |
| `confronta.sh` | mette i risultati dell'emulatore e di AWS uno accanto all'altro |
| `lambda-java.sh` | crea sull'emulatore una Lambda Java 21 e prova a chiamarla |
| `elimina.sh` | su AWS cancella lo stack e il bucket del codice; sull'emulatore vi rimanda a `ferma-emulatore.sh` |
| `prove/` | quello che è uscito a me |

`deploy.sh`, `prova.sh` ed `elimina.sh` vogliono `emulatore` o `aws` come argomento. Gli altri non ne hanno.

## Sull'emulatore

```sh
./avvia-emulatore.sh
./deploy.sh emulatore
./prova.sh emulatore
```

`avvia-emulatore.sh` la prima volta costruisce l'immagine, che scarica il pacchetto RPM da AWS. Poi avvia il container e aspetta finché `/__admin/health` risponde. L'emulatore scrive in `~/.aws/emulator/9297/` le chiavi valide solo per quella esecuzione, un file `config` e il certificato con cui serve HTTPS.

`usa emulatore`, in `comune.sh`, chiama `punta_all_emulatore`, che fa quello che dice la documentazione: imposta `AWS_ENDPOINT_URL` su `https://127.0.0.1:9297`, fa leggere a CLI e SDK le chiavi e il certificato dell'emulatore e toglie dall'ambiente le vostre credenziali. Se qualcosa va storto e la CLI finisce su AWS vero, `deploy.sh` ve lo dice subito nella prima riga: sull'emulatore l'account è `000000000000`.

La telemetria dell'emulatore parte accesa: manda ad AWS dati anonimi di uso ed errori, non le richieste né i dati delle risorse. `avvia-emulatore.sh` la spegne con `--telemetry off`.

## Su AWS

```sh
aws sts get-caller-identity
./deploy.sh aws
./prova.sh aws
./elimina.sh aws
```

Il primo comando serve a controllare l'account prima di creare qualcosa: gli script usano le credenziali e la regione della vostra shell (`AWS_PROFILE`, `AWS_REGION`; se la regione non c'è, Milano).

Lo stack crea due tabelle DynamoDB on-demand, tre code SQS, una regola sul bus `default` di EventBridge, un bucket S3 privato per le contabili, tre Lambda da 256 MB con un ruolo ciascuno, un'API REST di API Gateway e tre log group con un giorno di retention. Più il bucket per il codice, creato da `deploy.sh`.

**Attenzione:** l'API `POST /bonifici` è pubblica e senza autenticazione. Per una prova di pochi minuti va bene, ma lanciate `elimina.sh aws` appena avete finito. A me la prova è costata meno di un centesimo.

## Il giro

`prova.sh` apre due conti (il numero 100 con 1000.00 euro, il numero 200 con 50.00), poi chiama l'API:

| Bonifico | Cosa deve succedere |
|---|---|
| B1: 250.00 dal conto 100 al conto 200 | eseguito: contabile su S3, archiviata in DynamoDB, notifica `BonificoEseguito` |
| B1 di nuovo, stesso id | la coda FIFO lo riconosce dal deduplication id e non lo consegna |
| B2: 5000.00 dal conto 200 al conto 100 | rifiutato: saldo insufficiente, notifica `BonificoRifiutato` |
| B3: 10.00 dal conto 100 al conto 999 | rifiutato: il conto 999 non esiste, notifica `BonificoRifiutato` |
| B4: importo negativo | l'API risponde 400 e non mette niente in coda |

Insieme ai bonifici mette direttamente sulla coda un messaggio scritto a mano, che non è JSON: `esegui` va in errore, la coda lo riprova e dopo il terzo tentativo lo sposta tra gli scartati.

Poi aspetta, fino a 3 minuti per ogni cosa, che la contabile sia archiviata, che arrivino le tre notifiche e che lo scartato sia nella sua coda. Alla fine legge saldi, bucket, tabella e code. L'ultimo passo rifà B2 e B3 con la CLI, fuori dalla Lambda, con la stessa transazione che usa `esegui`.

`prova.sh` va lanciato su uno stack appena creato: se trova già dei conti si ferma e vi dice cosa rilanciare. Su AWS sono `elimina.sh` e `deploy.sh`, sull'emulatore `ferma-emulatore.sh`, `avvia-emulatore.sh` e `deploy.sh`.

`esegui` sposta i soldi con una sola `TransactWriteItems`: toglie l'importo dal conto di partenza se il saldo basta e lo aggiunge al conto di arrivo se esiste. L'id del bonifico fa da `ClientRequestToken`, così se la Lambda riparte dopo la transazione il conto non viene addebitato due volte.

## Com'è andata

La mattina del 13 novembre 2026, emulatore e Milano in parallelo. Il confronto completo lo dà `./confronta.sh`.

**Uguale nei due posti:**

```
Saldo del conto numero 100: 750
Saldo del conto numero 200: 300
File nel bucket delle contabili: contabili/B1.txt
Contabile archiviata in DynamoDB:
    Bonifico B1
    Dal conto 100 al conto 200
    Importo: 250.00 EUR
```

- B1 è stato addebitato una volta sola. Sull'emulatore i log di `esegui` contano 10 invocazioni (B1 una volta, B2, B3 e il messaggio scritto a mano tre volte ciascuno): il secondo B1 la coda FIFO non l'ha proprio consegnato. Su AWS ho controllato solo i saldi
- B4 riceve `400 {"errore":"importo non valido"}` da tutti e due
- la notifica `BonificoEseguito` passa da EventBridge alla coda Notifiche anche sull'emulatore
- il messaggio scritto a mano finisce tra gli scartati, e alla lettura risulta `letto 4 volte` in tutti e due: tre volte da `esegui`, una da `prova.sh`
- i saldi tornano senza decimali (`750`, non `750.00`), perché DynamoDB salva i numeri senza gli zeri finali; l'emulatore fa lo stesso

**I tempi:**

| | Emulatore | AWS Milano |
|---|---|---|
| deploy dello stack (`prove/*/deploy.txt`) | 7 secondi | 132 secondi |
| risposta dell'API ai cinque bonifici | da 9 a 39 ms | 797 ms il primo, poi da 78 a 97 ms |
| contabile archiviata, partendo dal primo bonifico | 1 secondo | 5 secondi |

Sull'emulatore i tempi sono quelli di un programma sulla stessa macchina, senza rete: dicono quanto saranno veloci i test.

**Diverso: i due bonifici rifiutati.** Su AWS `esegui` riceve la `TransactionCanceledException` con i motivi, uno per operazione, e pubblica `BonificoRifiutato`:

```
Notifiche:
    BonificoEseguito B1
    BonificoRifiutato B2 saldo insufficiente
    BonificoRifiutato B3 conto di arrivo inesistente
```

Sull'emulatore B2 e B3 finiscono tra gli scartati dopo tre tentativi, per due motivi diversi:

- **B2**: l'API di DynamoDB dell'emulatore risponde come AWS, `CancellationReasons` compreso (lo si vede nel passo 5 di `prova.sh`, fatto con la CLI). Dentro la Lambda però l'SDK JavaScript che l'emulatore mette a disposizione consegna l'eccezione senza `CancellationReasons`. `esegui` non sa dire perché la transazione è stata annullata e rilancia l'errore. È già tra le issue aperte su GitHub, dall'11 novembre.
- **B3**: qui la risposta è diversa già con la CLI. AWS annulla la transazione perché la condizione `attribute_exists(conto)` sul conto 999 non è rispettata (`[None, ConditionalCheckFailed]`). L'emulatore prova ad applicare `SET saldo = saldo + :importo` su un conto che non c'è e risponde `ValidationException: The provided expression refers to an attribute that does not exist in the item`. Con una `UpdateItem` singola, invece, l'emulatore controlla prima la condizione e risponde `ConditionalCheckFailedException` come AWS.

## Due cose che ho scritto in modo diverso per l'emulatore

Il template va bene così com'è su tutti e due, ma la prima versione sull'emulatore non partiva.

- **Il bus di EventBridge.** Avevo creato un bus `tried-it-first-banca` nel template. Sull'emulatore la regola non lo trovava (`Event bus tried-it-first-banca does not exist`), anche se con la CLI bus e regola si creano senza problemi. È un'issue aperta dal 25 ottobre. Ho usato il bus `default`.
- **La notifica di S3.** Con SAM avrei scritto un evento `S3` sulla funzione `archivia`. Il transform SAM dell'emulatore lo ignora: nel template elaborato mancano la configurazione delle notifiche del bucket e il permesso Lambda. Ho scritto le due risorse a mano (`NotificationConfiguration` sul bucket e `ArchiviaDaS3`), che è quello che SAM genera su AWS. Così l'emulatore consegna la notifica.

## E le Lambda in Java?

`lambda-java.sh` compila una classe Java, la carica sull'emulatore come Lambda `java21` e la chiama:

```
Stato della funzione: java21	Active
Invocazione:
    InvalidRuntimeException: The runtime java21 is not supported by this emulator, which runs the Python and node runtimes.
```

Per ora l'emulatore esegue Lambda in Node.js e Python. Accetta la funzione Java ma non la esegue, e lo dice chiaramente quando la chiamate. È tra le issue aperte, e per questo le Lambda della prova sono in Node.js. Lo script cancella funzione e ruolo alla fine.

## Problemi comuni

- **`Unable to locate credentials` sull'emulatore**: l'emulatore è fermo. Quando si ferma cancella le chiavi che aveva scritto in `~/.aws/emulator/9297/`. `docker ps` deve mostrare il container `aws-emulator`; se non c'è, `./avvia-emulatore.sh`.
- **`Lo stack è già stato usato`**: `prova.sh` vuole conti vuoti. Ricreate lo stack (o riavviate l'emulatore).
- **Le chiamate all'API sull'emulatore rispondono 404 `UnknownService`**: l'emulatore riconosce l'API dall'header `Host`. Con curl serve `-H "Host: <id>.execute-api.<regione>.amazonaws.com"` e l'indirizzo `https://127.0.0.1:9297/Prod/...`, come fa `prova.sh`.
- **Lo stack sull'emulatore finisce in `DELETE_FAILED` o `ROLLBACK_FAILED`**: il CloudFormation dell'emulatore prova a cancellare il deployment di API Gateway prima dello stage che lo usa (`Active stages pointing to this deployment must be moved or deleted`). Succede cancellando lo stack e nel rollback di un deploy fallito. Per questo `elimina.sh emulatore` non cancella niente e vi rimanda a `ferma-emulatore.sh`: l'emulatore tiene tutto in memoria, e riavviarlo è il modo più rapido per ripartire puliti.

## Cosa non ho provato

- Linux e Windows: gli script li ho lanciati solo su macOS. Su Linux AWS consiglia il pacchetto RPM o Debian invece di Docker.
- Un Mac Intel o un PC x86: `avvia-emulatore.sh` sceglie il pacchetto `x86_64`, ma non l'ho provato.
- Altre regioni AWS oltre a Milano.
- Lambda in Python, Step Functions e i test dell'emulatore dentro una pipeline di CI.

## Pulizia

```sh
./elimina.sh aws
./ferma-emulatore.sh
docker image rm aws-emulator
rm -rf ~/.aws/emulator
```

L'ultima riga cancella le chiavi e i certificati che l'emulatore lascia nella vostra home: fatela solo se non lo usate più.

## Link

- [L'annuncio di AWS](https://aws.amazon.com/about-aws/whats-new/2026/11/aws-emulator-now-available/)
- [La documentazione](https://docs.aws.amazon.com/emulator)
- [Il codice dell'emulatore e le issue](https://github.com/aws/aws-emulator)
