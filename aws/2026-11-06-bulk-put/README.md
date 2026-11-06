# 3.000 movimenti scartati e lo stato dice COMPLETED: il bulk put di DynamoDB

Il 5 novembre 2026 DynamoDB ha aggiunto il bulk put items: si mettono i file su S3 e DynamoDB li scrive in una tabella che esiste già, con una condizione per ogni riga. Prima, per caricare un file in una tabella esistente, serviva un programma con `PutItem` o `BatchWriteItem`, e `BatchWriteItem` le condizioni non le accetta. L'import da S3 che c'era già crea solo tabelle nuove.

Ho caricato a Milano lo stesso file di 10.000 movimenti in tre tabelle uguali: due con il bulk put, una con un programma Java che fa una `PutItem` per riga. Il file ha 3.000 righe che la condizione deve scartare. Poi ho caricato due file con una riga rotta.

In breve: le condizioni funzionano e le 3.000 righe vengono scartate tutte e tre le volte, ma il bulk put finisce in `COMPLETED` con `FailedItemCount` 0, e se non chiedete il report `CONDITION_CHECK_FAILURE` non lo scoprite da nessuna parte. Su 10.000 righe la `PutItem` ci ha messo 11 secondi e il bulk put circa 2 minuti, ma il bulk put costa circa 6 volte meno. Una riga tagliata a metà ferma la lettura di tutto il resto del file.

## Contenuto

```
template.yaml      quattro tabelle DynamoDB on-demand e il bucket S3 per i file e i report
carica/            progetto Maven: genera i file e fa il confronto con PutItem
comune.sh          regione, nome dello stack, immagine Docker e tre funzioni usate dagli altri script
deploy.sh          crea lo stack
genera.sh          compila il progetto Java e scrive i file in file/
prova.sh           tutta la prova: carica i file su S3, lancia i bulk put e la PutItem, conta e scarica i report
costi.sh           byte fatturati dal bulk put e unità di scrittura consumate dalla PutItem, in dollari
elimina.sh         cancella tutto
prove/eu-south-1/  i risultati della mia esecuzione
```

Le classi Java in `carica/src/main/java/carica/`:

```
Righe.java             le righe dei file: i movimenti di partenza, il file del giorno, i file da rompere
Riga.java              un movimento e la sua condizione
Condizione.java        nessuna, "solo se nuovo", "solo se la versione è più nuova"
RigaJson.java          una riga nel formato DynamoDB JSON del bulk put
GeneraFile.java        scrive i quattro file, rompendo la riga 5.000 di due di loro
CaricaConPutItem.java  carica il file del giorno con una PutItem per riga, 32 alla volta
```

## Le tabelle e i file

Ogni movimento ha `id` (la chiave), `conto`, `importo`, `versione` e `file`, cioè il nome del file da cui arriva. Così, a prova finita, si può contare cosa è stato scritto e da dove.

| Tabella | Prima | Poi |
|---|---|---|
| `tried-it-first-senza-report` | 10.000 movimenti, con il bulk put | il file del giorno con il bulk put, report `ERROR` |
| `tried-it-first-con-report` | 10.000 movimenti, con il bulk put | il file del giorno con il bulk put, report `ERROR` e `CONDITION_CHECK_FAILURE` |
| `tried-it-first-put-item` | 10.000 movimenti, con il bulk put | il file del giorno con `CaricaConPutItem` |
| `tried-it-first-righe-rotte` | vuota | i due file con la riga rotta, report `ERROR` e `CONDITION_CHECK_FAILURE` |

I 10.000 movimenti di partenza vanno da `M00001` a `M10000`, tutti alla versione 1. Il file del giorno, `file/giorno/giorno.json`, ha 10.000 righe:

| Righe | Movimenti | Condizione | Cosa deve succedere |
|---|---|---|---|
| 1-6.000 | `M10001`-`M16000`, nuovi | `attribute_not_exists(id)` | scritti |
| 6.001-8.000 | `M00001`-`M02000`, già in tabella | `attribute_not_exists(id)` | scartati |
| 8.001-9.000 | `M02001`-`M03000`, versione 2 | `attribute_not_exists(#v) OR #v < :nuova` | scritti |
| 9.001-10.000 | `M03001`-`M04000`, versione 1 come in tabella | `attribute_not_exists(#v) OR #v < :nuova` | scartati |

Una riga del file, la 8.001:

```json
{"Item":{"id":{"S":"M02001"},"conto":{"S":"C001"},"importo":{"N":"30.01"},"versione":{"N":"2"},"file":{"S":"giorno"}},"ConditionExpression":"attribute_not_exists(#v) OR #v < :nuova","ExpressionAttributeNames":{"#v":"versione"},"ExpressionAttributeValues":{":nuova":{"N":"2"}}}
```

I due file con la riga rotta, in `file/righe-rotte/`, hanno 10.000 movimenti nuovi senza condizione. In `senza-chiave.json` alla riga 5.000 manca l'`id`; in `troncata.json` la riga 5.000 è tagliata a metà:

```
{"Item":{"id":{"S":"T05000"},"conto":{"S":"C000"},"importo":
```

Il template non crea ruoli IAM: il bulk put legge e scrive su S3 con le credenziali di chi lo lancia. Le tabelle sono on-demand, ferme non costano niente; tutta la prova è costata meno di 2 centesimi di dollaro (vedi [I costi](#i-costi)).

## Cosa serve

| | Versione |
|---|---|
| Account AWS | uno di prova |
| Regione | io ho usato `eu-south-1` (Milano); gli script la prendono da `AWS_REGION` |
| AWS CLI | una versione che conosca `start-bulk-put-items` (io 2.39.5; la 2.38.1 non lo conosce) |
| Docker | per compilare e lanciare il codice Java senza installare JDK e Maven; io Docker Desktop |
| jq | per `costi.sh` |
| Sistema | macOS o Linux per gli script `.sh` |

L'immagine Docker è `maven:3.10.0-amazoncorretto-21` (Java 21, Maven 3.10.0). Il codice usa l'AWS SDK for Java 2.57.10.

L'utente AWS deve poter creare stack CloudFormation, tabelle DynamoDB e bucket S3, lanciare il bulk put (`dynamodb:StartBulkOperation`, `dynamodb:BulkPutItems`, `dynamodb:DescribeBulkOperation`), leggere le metriche di CloudWatch e scrivere con `PutItem`. Io ho usato un ruolo SSO con AdministratorAccess.

Ho provato tutto su macOS 27, Apple M2 Pro.

## 1. Controlla gli strumenti

```sh
aws --version
aws dynamodb help | grep -c start-bulk-put-items
aws sts get-caller-identity
docker info > /dev/null && echo "Docker c'è"
```

Il secondo comando deve stampare un numero maggiore di zero, altrimenti la CLI è troppo vecchia. Il terzo deve mostrare l'account su cui volete fare la prova.

Se la regione è una di quelle da abilitare (Milano lo è):

```sh
aws account get-region-opt-status --region-name eu-south-1
```

Deve rispondere `ENABLED`.

## 2. Lo stack e i file

```sh
export AWS_REGION=eu-south-1
./deploy.sh
./genera.sh
```

`deploy.sh` crea lo stack `tried-it-first-bulk-put` in meno di un minuto. `genera.sh` la prima volta scarica l'immagine Docker e le dipendenze Maven in `~/.m2`, poi scrive i quattro file in `file/` e ne conta le righe:

```
   10000 file/giorno/giorno.json
   10000 file/iniziali/iniziali.json
   10000 file/righe-rotte/senza-chiave.json
   10000 file/righe-rotte/troncata.json
   40000 total
```

I file non sono nel repository, perché pesano 5,6 MB e `genera.sh` li ricrea sempre uguali: ogni movimento si calcola dal suo numero, senza niente di casuale. Per controllare che siano quelli della mia prova:

```sh
sed -n 8001p file/giorno/giorno.json
sed -n 5000p file/righe-rotte/troncata.json
```

Devono stampare le due righe mostrate sopra, in [Le tabelle e i file](#le-tabelle-e-i-file).

## 3. La prova

```sh
./prova.sh
```

A me ci ha messo circa 7 minuti, quasi tutti di attesa: ogni bulk put passa i primi minuti a prepararsi. Cosa fa:

1. carica `file/` nel bucket, sotto `ingresso/`
2. carica i 10.000 movimenti di partenza in tre tabelle, tre bulk put insieme, e controlla che ce ne siano 10.000 in ognuna
3. fa partire insieme i due bulk put del file del giorno, quello dei file con la riga rotta e `CaricaConPutItem`
4. conta i movimenti nelle tabelle (`03-conteggi.txt`)
5. scarica i report da S3 in `report/` e ne conta le righe (`04-report.txt`)

Il bulk put è un'operazione asincrona: `start-bulk-put-items` restituisce subito l'ARN, e `prova.sh` controlla lo stato ogni 10 secondi fino a quando non è finita. I tempi in `tempi.txt` sono quindi precisi a 10 secondi. La risposta finale di ogni operazione la salva in `<passo>-<tabella>.json`.

Dopo qualche minuto, quando CloudWatch ha aggiornato le metriche:

```sh
./costi.sh
```

## Come è andata

La mia esecuzione, del 6 novembre 2026 a Milano, è in `prove/eu-south-1/`. Nei file ho sostituito l'ID dell'account con `<account>`, e nei nomi delle cartelle dei report con `account`.

### Il file del giorno

Tutte e tre le tabelle sono finite uguali (`03-conteggi.txt`):

```
senza-report: 16000 movimenti, 7000 dal file del giorno, 1000 alla versione 2
con-report: 16000 movimenti, 7000 dal file del giorno, 1000 alla versione 2
put-item: 16000 movimenti, 7000 dal file del giorno, 1000 alla versione 2
```

Le condizioni hanno funzionato: 7.000 righe scritte e 3.000 scartate, in tutti e tre i casi. Cambia il modo in cui lo si viene a sapere.

**Bulk put con il solo report `ERROR`** (`02-giorno-senza-report.json`, accorciato):

```json
"BulkOperationStatus": "COMPLETED",
"ProcessedItemCount": 10000,
"FailedItemCount": 0,
```

`ProcessedItemCount` conta le righe lette, non quelle scritte. `FailedItemCount` conta gli errori, e una condizione non rispettata per DynamoDB non è un errore. Su S3 c'è solo il manifest, che dice che il file è stato letto tutto:

```json
{"inputSourceArn":"arn:aws:s3:::tried-it-first-bulk-put-file-utfkhblboybx/ingresso/giorno/giorno.json","fileProcessingStatus":"FULLY_PROCESSED","processedRanges":[[0,9999]],"etag":"baffce9fc0b1d724a88fcf1c17dcb538"}
```

Delle 3.000 righe scartate non c'è traccia. La documentazione lo dice: senza `CONDITION_CHECK_FAILURE` DynamoDB non tiene né l'elenco né il numero delle righe scartate. Per scoprirle bisogna confrontare la tabella con il file.

**Bulk put con `ERROR` e `CONDITION_CHECK_FAILURE`.** Stessa risposta (`COMPLETED`, 10.000 lette, 0 fallite), ma su S3 c'è un file in più, `output/condition-check-failures/output-00000.json`, con 3.000 righe: le 2.000 già in tabella (indici da 6.000 a 7.999, contano da 0) e le 1.000 con la stessa versione (da 9.000 a 9.999). Ogni riga ha il movimento in tabella (`oldImage`) e quello del file (`newImage`):

```json
{"itemLocation":{"inputSourceArn":"arn:aws:s3:::tried-it-first-bulk-put-file-utfkhblboybx/ingresso/giorno/giorno.json","itemIndex":9000},"errorMessages":["The conditional request failed"],"newImage":{"Item":{"conto":{"S":"C001"},"file":{"S":"giorno"},"id":{"S":"M03001"},"importo":{"N":"40.01"},"versione":{"N":"1"}}},"oldImage":{"Item":{"conto":{"S":"C001"},"file":{"S":"iniziali"},"id":{"S":"M03001"},"importo":{"N":"40.01"},"versione":{"N":"1"}}}}
```

Le due operazioni sono identiche tranne che per una riga, il valore di `OutputType` in `prova.sh`:

```sh
SOLO_ERRORI='["ERROR"]'
TUTTI_I_REPORT='["ERROR","CONDITION_CHECK_FAILURE"]'
```

Il report pesa 1,3 MB per 3.000 righe, più del file da cui arrivano (1,9 MB per 10.000), perché ogni riga porta due copie del movimento.

**`PutItem`** (`02-giorno-put-item.txt`):

```
righe: 10000
scritte: 7000
scartate dalla condizione: 3000
tempo: 11,210 secondi
```

Ogni riga scartata arriva al programma come `ConditionalCheckFailedException`, nel momento in cui succede.

### Le righe rotte

L'operazione sui due file è finita in `FAILED` (`02-righe-rotte-righe-rotte.json`, accorciato):

```json
"BulkOperationStatus": "FAILED",
"ProcessedItemCount": 15000,
"ProcessedInputBytes": 1858981,
"TotalInputBytes": 2461922,
"FailedItemCount": 2,
"FailureCode": "ValidationException",
"FailureMessage": "Some of the items failed validation checks and were not ingested",
```

Nonostante il `FAILED`, in tabella ci sono 14.998 movimenti (`03-conteggi.txt`).

| | `senza-chiave.json` | `troncata.json` |
|---|---|---|
| Riga 5.000 | manca l'`id` | tagliata a metà |
| Errore nel report | `Missing the key id in the item` | `Expected DynamoDB type to be one of [S, N, B, BOOL, NULL, L, M, SS, NS, BS]. Remainder of the file will not be processed.` |
| Il report dice quale riga | sì, `itemIndex` 4999 | no |
| Il resto del file | caricato | non letto |
| Manifest | `FULLY_PROCESSED`, `[[0,9999]]` | `PARTIALLY_PROCESSED`, `[[0,4998]]` |
| Movimenti in tabella | 9.999 | 4.999 |

La riga senza chiave viene scartata e il file va avanti, mentre quella tagliata ferma la lettura: le 5.000 righe dopo, tutte valide, non sono state scritte. Per sapere dove ripartire c'è solo il manifest, con l'ultimo indice letto, 4.998.

L'errore non parla di una riga tagliata ma di un tipo DynamoDB sbagliato. La riga 5.000 finisce con `"importo":`, e la riga dopo comincia con `{"Item":`: sembra che DynamoDB abbia letto l'a capo come uno spazio e preso la riga dopo come valore di `importo`. Lo ricavo dal messaggio, la documentazione non lo dice.

### I tempi

Da `tempi.txt`:

| | Tempo |
|---|---|
| 10.000 movimenti di partenza, tre bulk put insieme | 107, 107 e 265 secondi |
| file del giorno, bulk put senza report | 138 secondi |
| file del giorno, bulk put con report | 117 secondi |
| file con le righe rotte, bulk put | 106 secondi |
| file del giorno, `PutItem`, 32 alla volta dal Mac | 12 secondi (11,2 misurati dal programma) |

Su 10.000 righe il bulk put passa quasi tutto il tempo a prepararsi. Lo stesso file, nelle stesse condizioni, ci ha messo 107 secondi in una tabella e 265 in un'altra. AWS pubblica numeri solo per il livello Accelerated, e su milioni di righe; io ho usato quello Standard, il default.

### I costi

Da `costi.sh` (`05-costi.txt`), con i prezzi di Milano del 6 novembre 2026: 0,1184 dollari per GB scritto con il bulk put Standard, 0,74 dollari per milione di unità di scrittura on-demand.

| | Fatturato | Dollari |
|---|---|---|
| file del giorno, bulk put (ognuna delle due tabelle) | 10.240.000 byte | 0,00113 |
| file del giorno, `PutItem` | 10.000 unità di scrittura | 0,00740 |
| file con le righe rotte, bulk put | 15.357.952 byte | 0,00169 |

Ogni movimento pesa meno di 100 byte, ma il bulk put ne ha fatturati 1.024 per riga: 10.240.000 byte sono 10.000 righe da 1 KB. Anche la `PutItem` conta un'unità per ogni scrittura fino a 1 KB. In tutti e due i casi le 3.000 righe scartate dalla condizione si pagano come quelle scritte.

Nel file con le righe rotte invece le due righe rotte non si pagano: 15.357.952 byte sono 14.998 righe da 1 KB, quante ne sono finite in tabella. Le 5.000 non lette dopo la riga tagliata non si pagano neanche loro.

Riga per riga, con movimenti fino a 1 KB, il bulk put costa circa 6 volte meno: un milione di righe fa 0,11 dollari contro 0,74. In `costi.sh` un GB è 1.073.741.824 byte; non ho trovato scritto se AWS lo conti così o come 1.000.000.000 byte, e in quel caso sarebbero 0,12 dollari per milione di righe e 0,00121 per il file del giorno.

Le unità di scrittura della `PutItem` le ho prese dalla metrica `ConsumedWriteCapacityUnits` di CloudWatch. Per le tabelle caricate con il bulk put la stessa metrica resta a 0: il bulk put non consuma la capacità della tabella, e non si vede nei grafici della tabella.

### Cosa non ho provato

- Il livello Accelerated, i file compressi e il formato Ion.
- File grandi: qui sono 10.000 righe per file, il bulk put è pensato per milioni.
- Tabelle con indici secondari, stream o TTL.
- Altre regioni oltre a Milano.
- La console e gli SDK per il bulk put: l'ho lanciato solo con la CLI.

I comandi per Linux non li ho eseguiti.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `argument operation: Found invalid choice 'start-bulk-put-items'` | CLI troppo vecchia: aggiornatela (passo 1) |
| `Docker non risponde: serve per il confronto con PutItem` | Avviate Docker e rilanciate `prova.sh` |
| `InvalidClientTokenId` o `UnrecognizedClientException` su `eu-south-1` | La regione non è abilitata sull'account (passo 1) |
| `ExpiredToken` | Credenziali scadute: `aws sso login` o nuove chiavi |
| `tried-it-first-...: mi aspettavo 10000 movimenti, ne ho trovati 16000.` | Avete rilanciato `prova.sh` su tabelle già usate: `./elimina.sh`, poi di nuovo dal passo 2 |
| `... ancora in corso dopo 1800 secondi` | Un bulk put non è finito in mezz'ora: guardate lo stato con `aws dynamodb list-bulk-operations` |
| `CloudWatch non ha ancora le scritture della PutItem: riprovate tra qualche minuto.` | CloudWatch aggiorna le metriche con qualche minuto di ritardo: aspettate e rilanciate `costi.sh` |
| `I prezzi in costi.sh sono quelli di eu-south-1` | Avete fatto la prova in un'altra regione: cambiate `DOLLARI_PER_GB_BULK` e `DOLLARI_PER_MILIONE_DI_SCRITTURE` con i prezzi della vostra |

## Pulizia

```sh
./elimina.sh
```

Svuota e cancella il bucket, poi cancella lo stack con le quattro tabelle. Controllo:

```sh
aws dynamodb list-tables --query "TableNames[?starts_with(@, 'tried-it-first')]"
aws cloudformation describe-stacks --stack-name tried-it-first-bulk-put
```

Il primo deve tornare vuoto, il secondo deve rispondere `Stack with id tried-it-first-bulk-put does not exist`. Sul computer restano `file/`, `carica/target/`, l'immagine Docker e le dipendenze in `~/.m2`:

```sh
rm -rf file carica/target
docker rmi maven:3.10.0-amazoncorretto-21
```

## Link

- Annuncio: https://aws.amazon.com/about-aws/whats-new/2026/11/amazon-dynamodb-now-supports-bulk-put-items/
- Formato dei file e condizioni: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/BulkPutItems.Format.html
- Report e manifest: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/BulkPutItems.Output.html
- Stati e codici di errore: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/BulkPutItems.Requesting.html
- Limiti e permessi: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/BulkPutItems.Considerations.html
- Prezzi: https://aws.amazon.com/dynamodb/pricing/on-demand/
