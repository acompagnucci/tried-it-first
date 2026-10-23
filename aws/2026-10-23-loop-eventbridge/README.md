# Il loop su EventBridge che Lambda dovrebbe fermare

Il 14 ottobre 2026 AWS ha annunciato che il [rilevamento dei loop ricorsivi](https://docs.aws.amazon.com/lambda/latest/dg/invocation-recursion.html) di Lambda copre anche i bus EventBridge custom: se una funzione pubblica sul bus che la attiva, Lambda conta i giri e intorno al 16° blocca l'invocazione successiva. Per SQS, SNS e S3 lo faceva già.

Ho scritto una Lambda che fa esattamente questo errore, in Java e in Node.js, e l'ho lanciata in due regioni. Sul bus EventBridge non si è mai fermata. La stessa logica su una coda SQS si è fermata al 16° giro.

## Contenuto

```
giro/                     progetto Maven
  src/main/java/giro/
    Giro.java             riceve l'evento dal bus e ne pubblica un altro sullo stesso bus
    GiroSqs.java          la stessa cosa con una coda SQS
    Registro.java         scrive nel log il giro, la versione dell'SDK e l'header di tracciamento
giro-node/index.mjs       la stessa funzione in Node.js, con l'SDK incluso nel runtime
codice.yaml               bucket S3 dove sam deploy carica i jar
template.yaml             bus, code, ruolo e le sette funzioni (SAM)
compila.sh                compila il jar con quattro versioni dell'SDK, dentro Docker
deploy.sh                 compila, crea il bucket e fa il deploy
prova.sh                  manda il primo evento e aspetta che il loop si fermi
risultati.sh              metrica RecursiveInvocationsDropped e coda dei fermati
elimina.sh                cancella tutto
prove/                    i risultati delle mie esecuzioni, una cartella per regione
```

## Le funzioni

Tutte su arm64, 512 MB, Java 21 tranne una in Node.js 24. Ogni funzione ha la sua regola sul bus, che filtra sul campo `prova` dell'evento.

| Funzione | SDK | Protezione dai loop | Attivata da |
|---|---|---|---|
| `tried-it-first-protetta` | 2.46.7 | attiva (default) | bus EventBridge |
| `tried-it-first-libera` | 2.46.7 | tolta (`RecursiveLoop: Allow`) | bus EventBridge |
| `tried-it-first-sdk-2-17-135` | 2.17.135 | attiva | bus EventBridge |
| `tried-it-first-sdk-2-21-23` | 2.21.23 | attiva | bus EventBridge |
| `tried-it-first-sdk-2-21-24` | 2.21.24 | attiva | bus EventBridge |
| `tried-it-first-node` | JavaScript 3.1105.0, quello del runtime `nodejs24.x` | attiva | bus EventBridge |
| `tried-it-first-sqs` | 2.46.7 | attiva | coda SQS |

Ogni giro scrive nel log il proprio numero e pubblica l'evento del giro dopo. Al giro 50 la funzione smette di pubblicare: è il freno che ho messo io, così la prova non costa niente anche se AWS non interviene.

Le versioni dell'SDK Java non sono a caso. Per riconoscere il loop serve che l'SDK rimandi l'header di tracciamento, e la [documentazione](https://docs.aws.amazon.com/lambda/latest/dg/invocation-recursion.html#invocation-recursion-supportedsdks) chiede almeno la 2.17.135 per Java 8 e 11, e almeno la 2.21.24 per Java 21. La 2.21.23 è quella subito prima.

Retry a zero sia sulla regola sia sull'invocazione asincrona: ogni giro è un evento solo. Se Lambda blocca un'invocazione, l'evento finisce nella coda `tried-it-first-fermati`, che per la funzione SQS è anche la coda dei messaggi scartati (dopo 2 ricezioni).

## Cosa serve

| | Versione |
|---|---|
| Account AWS | uno di prova; la prova costa meno di un centesimo |
| Regione | io ho usato `eu-south-1` (Milano) e `eu-west-1` (Irlanda) |
| AWS CLI | 2 (io 2.38.1) |
| SAM CLI | io 1.169.0 |
| Docker | per compilare; non servono né Java né Maven sul computer, e nemmeno Node.js |
| Sistema | macOS o Linux per gli script `.sh` |

L'utente AWS deve poter creare stack CloudFormation, ruoli IAM, bucket S3, code SQS, bus EventBridge e funzioni Lambda. Io ho usato un ruolo SSO con AdministratorAccess su un account vuoto.

Ho provato tutto su macOS 27, Apple M2 Pro.

## 1. Controlla gli strumenti

```sh
aws --version
sam --version
docker info > /dev/null && echo "docker ok"
aws sts get-caller-identity
```

L'ultimo comando deve mostrare l'account su cui volete fare la prova.

Se la regione è una di quelle da abilitare (Milano lo è):

```sh
aws account get-region-opt-status --region-name eu-south-1
```

Deve rispondere `ENABLED`.

## 2. Deploy

```sh
export AWS_REGION=eu-south-1
./deploy.sh
```

Prima compila: scarica l'immagine `maven:3.10.0-amazoncorretto-21` e costruisce quattro jar in `pacchetti/`, uno per versione dell'SDK. La prima volta Maven scarica le dipendenze in `~/.m2`, e ci vogliono un paio di minuti. Poi crea lo stack `tried-it-first-codice` (il bucket) e lo stack `tried-it-first-giro`. Alla fine stampa:

```
Key                 Bus
Value               tried-it-first-giro
Key                 CodaFermati
Value               https://sqs.eu-south-1.amazonaws.com/<account>/tried-it-first-fermati
```

Controllo che la protezione sia configurata come ci si aspetta:

```sh
aws lambda get-function-recursion-config --function-name tried-it-first-protetta
aws lambda get-function-recursion-config --function-name tried-it-first-libera
```

```
{ "RecursiveLoop": "Terminate" }
{ "RecursiveLoop": "Allow" }
```

## 3. Le prove

Una alla volta, o tutte insieme in background:

```sh
./prova.sh protetta
./prova.sh libera
./prova.sh sdk-2.17.135
./prova.sh sdk-2.21.23
./prova.sh sdk-2.21.24
./prova.sh node
./prova.sh sqs
```

Ogni prova manda il primo evento con `giro: 1` (o il primo messaggio con corpo `1` sulla coda), aspetta che nel log non compaiano giri nuovi per 30 secondi e salva le righe in `prove/<regione>/<prova>.txt`. Ci mette circa un minuto.

```
protetta: 50 giri, dettaglio in prove/eu-south-1/protetta.txt
```

Dopo un paio di minuti, quando la coda SQS ha scartato il messaggio bloccato:

```sh
./risultati.sh
```

Salva in `prove/<regione>/fermati.txt` la metrica `RecursiveInvocationsDropped` delle ultime 3 ore per ogni funzione e il corpo dei messaggi nella coda `tried-it-first-fermati`. Legge i messaggi senza cancellarli, ma per un minuto non si vedono: se lo rilanciate subito, la coda sembra vuota.

Per rifare la prova in Irlanda: `export AWS_REGION=eu-west-1` e di nuovo dal passo 2.

## Come è andata

Le mie esecuzioni sono in `prove/eu-south-1/` e `prove/eu-west-1/`. In Irlanda ho lanciato solo `protetta`, `node` e `sqs`. La prova `node` l'ho fatta con un deploy a parte: il suo `risultati.sh` è in `fermati-node.txt`, con la coda dei fermati vuota e `None` per la funzione Node.

### Quanti giri

| Funzione | Milano | Irlanda |
|---|---|---|
| bus EventBridge, protezione attiva | 50 | 50 |
| bus EventBridge, protezione tolta | 50 | |
| bus EventBridge, SDK 2.17.135 | 50 | |
| bus EventBridge, SDK 2.21.23 | 50 | |
| bus EventBridge, SDK 2.21.24 | 50 | |
| bus EventBridge, Node.js 24 | 50 | 50 |
| coda SQS, protezione attiva | **16** | **16** |

50 è il freno nel codice: al giro 50 la funzione smette di pubblicare.

`RecursiveInvocationsDropped`, in tutte e due le regioni: nessun dato (`None`) per le funzioni sul bus, 2 per quella su SQS. Nella coda dei fermati c'è un solo messaggio, con corpo `17`: il giro che Lambda non ha lasciato partire. Sulla coda SQS il conto è 2 perché il messaggio viene ricevuto due volte prima di finire nella coda dei fermati, e Lambda lo blocca tutte e due le volte.

### Il contatore

Lambda conta i giri nel campo `Lineage` dell'header di tracciamento X-Ray, che l'SDK rimanda a ogni chiamata. `Registro.java` lo scrive nel log. Dalla funzione protetta a Milano:

```
prova=protetta giro=1  ... Lineage=1:7418c873:0
prova=protetta giro=2  ... Lineage=2:7418c873:1
...
prova=protetta giro=16 ... Lineage=16:7418c873:15
prova=protetta giro=17 ... Lineage=17:7418c873:16
...
prova=protetta giro=50 ... Lineage=50:7418c873:49
```

Il contatore arriva a 50 attraverso EventBridge: il giro viene contato, ma nessuno blocca l'invocazione. Con Node.js è uguale, `Lineage=50:ee9bada5:49` al giro 50 a Milano.

### L'SDK

Nel log c'è anche dove il runtime mette l'header: su Java 21 la variabile d'ambiente `_X_AMZN_TRACE_ID` è vuota, l'header è nella system property `com.amazonaws.xray.traceHeader`. Su Node.js 24 è nella variabile d'ambiente.

| SDK | Dove cerca l'header | `Lineage` nel giro 50 |
|---|---|---|
| 2.17.135 | solo nella variabile d'ambiente | `1:...:0` |
| 2.21.23 | nella system property e nella variabile | `50:...:49` |
| 2.21.24 | nella system property e nella variabile | `50:...:49` |
| 2.46.7 | nella system property e nella variabile | `50:...:49` |

Con la 2.17.135 su Java 21 l'header non viene rimandato: ogni giro parte con una traccia nuova e per Lambda è sempre il primo. Sul bus non cambia niente, perché il loop non si ferma comunque. Su SQS con questa versione non ho provato.

La 2.21.23, una versione prima della minima indicata per Java 21, il contatore lo porta avanti come le altre. Dove cerca l'header si vede dentro il jar:

```sh
unzip -p pacchetti/giro-2.21.23.jar \
  software/amazon/awssdk/awscore/internal/interceptor/TracingSystemSetting.class | strings | grep -i xray
```

### La velocità

Dal terzo giro in poi, sul bus EventBridge, un giro dura circa 130 ms: 50 giri in 11-13 secondi, compresi gli avvii a freddo. A quel ritmo, senza il freno, un evento solo farebbe più di 600.000 giri al giorno. Questo è un calcolo: io non ho lasciato girare niente oltre i 50.

### Cosa non ho provato

- Python e gli altri runtime, e altre regioni oltre a Milano e Irlanda.
- Node.js con un SDK diverso da quello del runtime.
- La mail e la notifica nell'Health Dashboard: AWS scrive che possono arrivare fino a 3 ore e mezza dopo il blocco.
- EventBridge Pipes e il bus `default`: l'annuncio parla solo dei bus custom.
- L'SDK 2.17.135 su SQS.
- Un loop con più funzioni in mezzo.

I comandi per Linux non li ho eseguiti (`risultati.sh` usa `date -v`, con un ripiego per `date -d`). Se a voi sul bus si ferma al 16°, apritemi una issue: mi interessa sapere su quale regione e in che giorno.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Cannot connect to the Docker daemon` | Docker non è avviato |
| `InvalidClientTokenId` o `UnrecognizedClientException` su `eu-south-1` | La regione non è abilitata sull'account (passo 1) |
| `ExpiredToken` | Credenziali scadute: `aws sso login` o nuove chiavi |
| `prova.sh` resta fermo per 5 minuti e scrive 0 giri | La funzione non è partita: guardate il log in CloudWatch, `/aws/lambda/tried-it-first-<prova>` |
| `risultati.sh` mostra la coda dei fermati vuota | L'avete appena letto: i messaggi tornano visibili dopo un minuto |

## Pulizia

```sh
./elimina.sh
```

Cancella lo stack delle funzioni, svuota e cancella il bucket, cancella i gruppi di log. Se avete fatto la prova in due regioni, va lanciato in tutte e due con `AWS_REGION` impostata. Controllo:

```sh
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE --query 'StackSummaries[].StackName'
aws logs describe-log-groups --log-group-name-prefix /aws/lambda/tried-it-first --query 'logGroups[].logGroupName'
```

Devono tornare vuoti. Le metriche di CloudWatch restano per conto loro e scadono da sole. L'immagine Docker resta sul computer: `docker rmi maven:3.10.0-amazoncorretto-21`.

## Link

- Annuncio: https://aws.amazon.com/about-aws/whats-new/2026/10/lambda-recursive-loops-eventbridge-custom
- Documentazione: https://docs.aws.amazon.com/lambda/latest/dg/invocation-recursion.html
- `RecursiveLoop` in SAM: https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-resource-function.html
- Prezzi Lambda: https://aws.amazon.com/lambda/pricing/
