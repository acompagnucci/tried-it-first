# Lo stesso codice DynamoDB su AWS e su PostgreSQL: ExtendDB 1.0

Il 18 novembre 2026 AWS ha rilasciato la versione 1.0 di ExtendDB, un server open source che risponde alle API di DynamoDB e salva i dati in un database che gestite voi: PostgreSQL, SQLite, MongoDB o Cassandra. Al codice che usa DynamoDB basta cambiare l'endpoint. AWS lo mantiene ma lo scrive chiaro: non è DynamoDB e non è un servizio AWS, lo installate e lo fate girare voi.

Ho preso un piccolo programma Java che fa bonifici tra due conti con una transazione di DynamoDB e l'ho fatto girare due volte senza cambiare una riga, puntandolo prima su DynamoDB a Milano e poi su ExtendDB sul mio Mac, con sotto PostgreSQL 18.4. Poi ho guardato cosa c'era dentro Postgres.

In breve: nei cinque bonifici di prova le risposte sono uguali, eccezioni e motivi del rifiuto compresi, e cambia solo il testo di un messaggio d'errore. Anche lo stream ha gli stessi record. Si comportano in modo diverso con 100 bonifici sullo stesso conto partiti insieme: a Milano ne passano tra 21 e 27 e gli altri tornano indietro con `TransactionConflict`, ExtendDB li mette in fila e li esegue tutti. Anche il TTL va in un altro modo: ExtendDB ha cancellato i 1.000 movimenti scaduti 100 al minuto, a Milano sono rimasti tutti lì per mezz'ora e poi sono spariti insieme.

## Cosa serve

Ho provato su un Mac M2 Pro con 16 GB di RAM e macOS 27.2.1, con:

- Docker 29.9.3 con Compose 5.7.0
- AWS CLI 2.39.5 e jq 1.7.1
- le immagini `extenddb/extenddb-postgres:1.0.0`, `postgres:18.4` e `maven:3.10.0-amazoncorretto-21` (gli script le scaricano se mancano)
- per la parte su AWS: un account dove potete creare tabelle DynamoDB; io ho usato Milano, `eu-south-1`

Java e Maven non servono sul Mac: il programma si compila e gira nel container di Maven, con Java 21 e l'SDK AWS per Java 2.59.5.

## I file

| File | Cosa fa |
|---|---|
| `extenddb/compose.yaml` | PostgreSQL 18.4 ed ExtendDB 1.0.0, con il passo di `extenddb init` che crea database, catalogo e utente amministratore |
| `extenddb/password-di-prova.env` | le password di Postgres e dell'amministratore di ExtendDB: vanno bene solo sul vostro Mac |
| `avvia-extenddb.sh` | avvia tutto, copia il certificato TLS di ExtendDB e crea l'utente `banca` con la sua chiave |
| `ferma-extenddb.sh` | ferma i container e cancella dati, chiave e certificato |
| `comune.sh` | nomi delle tabelle e le funzioni usate dagli altri script: `usa` punta la CLI ad AWS o a ExtendDB, `banca` lancia il programma Java nel container |
| `compila.sh` | compila `banca/` nel container di Maven |
| `crea-tabelle.sh` | crea le due tabelle con `aws dynamodb create-table`, lo stesso comando nei due posti |
| `prova.sh` | i bonifici, lo stream, i bonifici in parallelo; scrive in `prove/aws` o `prove/extenddb` |
| `scadenze.sh` | scrive 1.000 movimenti già scaduti e conta ogni minuto quanti ne restano |
| `dentro-postgres.sh` | le query su Postgres che mostrano dove finiscono tabelle, item, stream e token |
| `confronta.sh` | mette a confronto i risultati di AWS e di ExtendDB |
| `elimina-tabelle.sh` | cancella le due tabelle |
| `banca/` | il progetto Maven |
| `prove/` | quello che è uscito a me |

Gli script che parlano con DynamoDB vogliono `aws` o `extenddb` come argomento.

Le classi Java in `banca/src/main/java/banca/`:

| Classe | Cosa fa |
|---|---|
| `Conti.java` | apre un conto, legge il saldo, esegue un bonifico con una `TransactWriteItems` e capisce perché è stato rifiutato |
| `Bonifico.java` | id, conto di partenza, conto di arrivo, importo |
| `Esito.java` | eseguito, oppure rifiutato per conflitto, saldo insufficiente o conto di arrivo inesistente |
| `ProvaBonifici.java` | i cinque bonifici della prima prova |
| `ProvaInParallelo.java` | 100 bonifici sullo stesso conto partiti nello stesso istante |
| `CaricaScadenze.java` | 1.000 movimenti con la scadenza già passata |
| `Uscita.java` | lo stream su cui stampano i programmi |

Nel codice non c'è nessun endpoint: l'SDK lo prende dalla variabile `AWS_ENDPOINT_URL`, come la CLI.

## Su ExtendDB

```sh
./avvia-extenddb.sh
./compila.sh
./crea-tabelle.sh extenddb
./prova.sh extenddb
./scadenze.sh extenddb 30
./dentro-postgres.sh
```

Il secondo argomento di `scadenze.sh` è il numero massimo di minuti da aspettare; se manca, 180. `prova.sh` chiama già `dentro-postgres.sh`, che si può rilanciare da solo quando volete.

`avvia-extenddb.sh` lancia `docker compose`: prima Postgres, poi `extenddb init`, che crea i database `extenddb_catalog` ed `extenddb`, l'account e l'utente amministratore, e genera un certificato autofirmato. Poi parte il server, in HTTPS sulla porta 18443 e solo su `127.0.0.1`.

ExtendDB vuole le richieste firmate con SigV4, come AWS, con chiavi create da lui. Lo script crea l'utente `banca` con una policy che apre solo le tabelle `tried-it-first-*`:

```json
{"Effect": "Allow", "Action": "dynamodb:*", "Resource": "arn:aws:dynamodb:*:*:table/tried-it-first-*"}
```

e scrive la sua chiave in `extenddb/chiave.env`. Il primo `ListTables` che ho provato è stato negato, con lo stesso messaggio di IAM: la policy non copre `table/*`.

`usa extenddb`, in `comune.sh`, legge quella chiave e imposta `AWS_ENDPOINT_URL=https://127.0.0.1:18443` e `AWS_CA_BUNDLE` sul certificato. Per la CLI basta questo. Java invece `AWS_CA_BUNDLE` non lo legge: il programma gira con un truststore che contiene il certificato di ExtendDB (`extenddb/certificato.p12`, creato da `avvia-extenddb.sh` con `keytool`).

## Su AWS

```sh
aws sts get-caller-identity
./crea-tabelle.sh aws
./prova.sh aws
./scadenze.sh aws 180
./elimina-tabelle.sh aws
```

Il primo comando serve a controllare l'account prima di creare qualcosa: gli script usano le credenziali e la regione della vostra shell (`AWS_PROFILE`, `AWS_REGION`; se la regione non c'è, Milano). Le tabelle sono due, on-demand: `tried-it-first-conti`, con lo stream attivo, e `tried-it-first-movimenti`, con il TTL sull'attributo `scade`. Le prove fanno poche migliaia di scritture: a me sono costate pochi centesimi.

`prova.sh` va lanciato su tabelle appena create, perché legge lo stream dall'inizio. Per rifarlo: `elimina-tabelle.sh` e `crea-tabelle.sh`.

## Le prove

### I cinque bonifici

`prova.sh` lancia `ProvaBonifici`, che apre il conto `C001` con 1000 euro e il conto `C002` con 0, poi fa cinque bonifici. `Conti.esegui` sposta i soldi con una sola `TransactWriteItems`: toglie l'importo da `C001` se il saldo basta e lo aggiunge al conto di arrivo se esiste. L'id del bonifico fa da `ClientRequestToken`, e sul prelievo c'è `ReturnValuesOnConditionCheckFailure=ALL_OLD`, così quando la condizione non è rispettata nella risposta c'è anche il conto com'era.

Il secondo bonifico, uguale nei due posti:

```
2. Bonifico di 5000 euro da C001 a C002
   rifiutato: TransactionCanceledException, HTTP 400
   messaggio: Transaction cancelled, please refer cancellation reasons for specific reasons [ConditionalCheckFailed, None]
   motivo 1: ConditionalCheckFailed (The conditional request failed), item com'era: conto=C001, saldo=700
   motivo 2: None
   saldi: C001 700, C002 300
```

Il terzo, verso `C999`, dà `[None, ConditionalCheckFailed]` in tutti e due. Il quarto rimanda il primo con lo stesso id: risponde `eseguito` e i saldi restano fermi, sia a Milano sia su ExtendDB. Il quinto usa lo stesso id con 400 euro invece di 300, e qui c'è l'unica riga diversa di tutta l'uscita, il messaggio:

```
-   messaggio: Specified idempotent token was used with different request parameters within the idempotency window
+   messaggio: The request uses the same client token as a previous, but different, request.
```

Il codice d'errore, `IdempotentParameterMismatchException`, e lo stato HTTP 400 sono gli stessi. Il confronto completo è in `prove/confronto.txt`.

### Lo stream

Dopo i bonifici `prova.sh` legge con `aws dynamodbstreams` tutti gli shard dello stream della tabella dei conti. Tutti e due ne hanno 4, e dentro ci sono gli stessi record:

```
conto	evento	saldo prima	saldo dopo
C001	INSERT	-	1000
C001	MODIFY	1000	700
C002	INSERT	-	0
C002	MODIFY	0	300
```

Il bonifico rimandato con lo stesso id non lascia record.

### Dentro Postgres

`dentro-postgres.sh` fa quattro query con `psql` nel container di Postgres. ExtendDB usa due database: `extenddb_catalog`, con tabelle, utenti, policy e chiavi, ed `extenddb`, con i dati. Ogni tabella DynamoDB è una tabella Postgres che si chiama `_ddb_` più il suo id nel catalogo, con due colonne:

```
  pk  |                    item_data
------+-------------------------------------------------
 C001 | {"conto": {"S": "C001"}, "saldo": {"N": "700"}}
 C002 | {"conto": {"S": "C002"}, "saldo": {"N": "300"}}
```

Lo stream è nella tabella `stream_records`, i token dei bonifici in `idempotency_tokens`, con un'impronta della richiesta accanto. La tabella dei token non dipende dalla tabella DynamoDB: se cancellate e ricreate le tabelle, i token di prima restano lì.

### 100 bonifici sullo stesso conto

`ProvaInParallelo` apre `C010` con 10.000 euro e `C011` con 0, prepara 100 bonifici da 100 euro da `C010` a `C011` su 100 thread e li fa partire insieme. Alla fine controlla che i saldi tornino con i bonifici eseguiti. `prova.sh` lo lancia 5 volte con un tentativo per bonifico e 5 volte con fino a 50 tentativi, con una pausa casuale fino a 100 ms dopo ogni conflitto.

| | Milano | ExtendDB |
|---|---|---|
| un tentativo: eseguiti | 22, 27, 25, 21, 26 | 100 in tutte e 5 le corse |
| un tentativo: rifiutati con `TransactionConflict` | 78, 73, 75, 79, 74 | 0 |
| fino a 50 tentativi: eseguiti | 100 in tutte e 5 le corse | 100 in tutte e 5 le corse |
| fino a 50 tentativi: tentativi in tutto | 331, 366, 335, 373, 331 | 100 |

DynamoDB annulla una transazione che tocca un item su cui ce n'è già un'altra in corso. ExtendDB su Postgres la mette in coda sul lock della riga e la esegue dopo: è scritto nella sua pagina delle differenze. Il codice che riprova dopo un `TransactionConflict` su ExtendDB non viene mai usato, e un errore lì dentro lo scoprireste solo su DynamoDB.

Con i nuovi tentativi lo stesso `ClientRequestToken` funziona: una transazione annullata per conflitto si può rimandare con lo stesso id, e DynamoDB la esegue.

I tempi (da 0,22 a 0,31 secondi con un tentativo in tutti e due, circa un secondo a Milano con i nuovi tentativi) non li confronto: ExtendDB era sulla stessa macchina del programma, Milano a qualche centinaio di chilometri.

### Il TTL

`scadenze.sh` lancia `CaricaScadenze`, che scrive 1.000 movimenti con l'attributo `scade` a un minuto prima, poi conta con una `Scan` ogni minuto quanti ne restano.

ExtendDB li ha cancellati 100 al minuto, come dice la sua documentazione: ogni 60 secondi un giro da 100 elementi per tabella, valori fissi.

```
07:16:34 minuto 0: 1000 movimenti ancora in tabella
07:17:35 minuto 1: 900 movimenti ancora in tabella
...
07:25:35 minuto 9: 100 movimenti ancora in tabella
07:26:34 minuto 10: 0 movimenti ancora in tabella
```

A Milano il TTL l'avevo attivato con `crea-tabelle.sh` subito prima. AWS scrive che ci mette circa un'ora ad attivarsi su tutte le partizioni e che poi cancella gli elementi scaduti "entro pochi giorni". Per 29 minuti i movimenti sono rimasti tutti e 1.000, poi sono spariti tutti insieme tra un controllo e l'altro:

```
06:15:14 minuto 0: 1000 movimenti ancora in tabella
...
06:44:15 minuto 29: 1000 movimenti ancora in tabella
06:45:14 minuto 30: 0 movimenti ancora in tabella
```

È una corsa sola: dice cosa è successo a me il 20 novembre a Milano, non quanto ci mette di solito.

## Problemi comuni

- **`Manca extenddb/chiave.env`**: ExtendDB non è stato avviato con `avvia-extenddb.sh`, oppure è stato fermato con `ferma-extenddb.sh`, che cancella anche la chiave.
- **`Error: HTTP 409: IAM user already exists`**: avete cancellato `extenddb/chiave.env` a mano ma i dati di ExtendDB sono ancora lì, utente compreso. `./ferma-extenddb.sh` e `./avvia-extenddb.sh` ripartono da zero.
- **`AccessDeniedException` su `ListTables`**: è la policy dell'utente `banca`, che apre solo `table/tried-it-first-*`. ExtendDB controlla i permessi come IAM.
- **`PKIX path building failed` dal programma Java**: il truststore non contiene il certificato di ExtendDB. Lo rigenera `avvia-extenddb.sh`, che va rilanciato anche dopo aver ricreato i volumi.
- **Lo stream sembra mezzo vuoto**: una tabella nuova ha più shard, 4 nelle mie prove, e i record di un conto stanno in uno solo. `prova.sh` li legge tutti.
- **A Milano i movimenti scaduti sono ancora lì**: DynamoDB impiega circa un'ora ad attivare il TTL su tutte le partizioni e cancella gli elementi scaduti "entro pochi giorni". Finché non li cancella, `Scan` e `Query` li restituiscono.

## Cosa non ho provato

- Linux e Windows: gli script li ho lanciati solo su macOS.
- Gli altri backend: SQLite, MongoDB e Cassandra.
- ExtendDB su un server vero, con Postgres su RDS o Aurora, e più client in rete.
- Indici secondari, Query, PartiQL, backup, import ed export.

## Pulizia

```sh
./elimina-tabelle.sh aws
./ferma-extenddb.sh
docker image rm extenddb/extenddb-postgres:1.0.0 maven:3.10.0-amazoncorretto-21
```

`ferma-extenddb.sh` cancella anche i volumi di Docker con i dati di Postgres. Su AWS, dopo `elimina-tabelle.sh`, `aws dynamodbstreams list-streams` mostra ancora gli stream delle tabelle cancellate: DynamoDB li lascia leggibili per 24 ore e poi li toglie da solo. Il repository Maven in `~/.m2` resta: le dipendenze scaricate da `compila.sh` le trovate lì.

## Link

- [L'annuncio di AWS](https://aws.amazon.com/about-aws/whats-new/2026/11/aws-extenddb-1-0/)
- [Il codice di ExtendDB](https://github.com/ExtendDB/extenddb)
- [Le differenze da DynamoDB, scritte da loro](https://github.com/ExtendDB/extenddb/blob/main/docs/differences-from-dynamodb.md)
