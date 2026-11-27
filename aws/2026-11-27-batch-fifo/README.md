# Batch da 10.000 messaggi da una coda SQS FIFO

Dal 20 novembre 2026 una Lambda in provisioned mode può leggere una coda SQS FIFO a batch fino a 10.000 messaggi; prima il massimo era 10. Ho messo in coda 10.000 bonifici, a Milano, e ho guardato tre cose: quanti messaggi arrivano davvero in un'invocazione, quanto ci vuole a svuotare la coda e cosa succede quando un bonifico a metà batch fallisce.

## Cosa c'è nella cartella

- `bonifici/`: il progetto Maven, Java 21. Dentro ci sono la Lambda (`Gestore` e le sue tre versioni), `Invia`, che mette i bonifici in coda, e `Riassunto`, che legge da DynamoDB cosa è successo.
- `template.yaml`: cinque code FIFO, ognuna con la sua Lambda e il suo event source mapping, più una coda per i messaggi falliti e due tabelle DynamoDB.
- `deploy.sh`, `elimina.sh`: creano e cancellano lo stack e il bucket del codice.
- `prova-una.sh`: una corsa, cioè i bonifici in coda e la lettura accesa finché la coda non si svuota.
- `prova.sh`: tutte le corse della prova, con l'output in `prove/`.
- `comune.sh`, `compila.sh`: nomi comuni, e Java e Maven lanciati in Docker.
- `controlla-emulatore.sh`, `emulatore/`: il controllo per farla girare su AWS Emulator, nell'appendice in fondo.
- `prove/`: gli output delle mie corse.

## Come funziona la prova

Ogni bonifico è un messaggio JSON, e il message group è il conto (`conto-500`). I bonifici di ogni conto sono numerati da 1 in su, 10 per conto quando i conti sono 1.000: i dispari sono accrediti, i pari addebiti dello stesso importo dell'accredito prima. Se i bonifici arrivano in ordine il saldo non va mai sotto zero, e alla fine torna a zero.

La Lambda divide il batch per conto. Per ogni conto legge da DynamoDB il saldo e il numero dell'ultimo bonifico registrato, applica i bonifici in ordine e riscrive il conto, con la condizione che nel frattempo nessuno l'abbia cambiato. Un bonifico con un numero già visto viene scartato come doppione. Un addebito che porterebbe il saldo sotto zero viene rifiutato. Per ogni invocazione la Lambda salva quanti messaggi ha ricevuto, quanti conti, quanti byte pesava l'evento, e quando è iniziata e finita.

Una corsa va così:

1. `Invia` mette in coda i bonifici, con la lettura spenta;
2. `prova-una.sh` accende la lettura e aspetta che la coda risulti vuota per tre controlli di fila, a 5 secondi l'uno dall'altro;
3. spegne la lettura e lancia `Riassunto`.

I tempi li misura la Lambda all'inizio e alla fine dell'handler. "Dalla prima all'ultima invocazione" va dall'inizio della prima alla fine dell'ultima, e non comprende l'accensione della lettura.

Nella prova 3 tre Lambda hanno le variabili `CONTO_GUASTO=500` e `BONIFICO_GUASTO=5`: il controllo antiriciclaggio, finto, non risponde la prima volta che riceve il bonifico 5 del conto 500, che è un accredito. Al secondo tentativo risponde. Le tre Lambda cambiano solo in cosa fanno a quel punto:

- `RimandaIlRestoDelConto` rimanda indietro il bonifico fallito e quelli dopo dello stesso conto, come chiede la documentazione per le code FIFO;
- `RimandaSoloIlFallito` rimanda indietro solo il bonifico fallito e va avanti con gli altri;
- `LanciaUnEccezione` non gestisce l'errore, e l'eccezione esce dall'handler.

## Requisiti

- Docker: Java 21 e Maven girano nel container `maven:3.10.0-amazoncorretto-21`, quindi sul computer non servono.
- AWS CLI v2 e jq.
- Un account AWS in cui potete creare stack CloudFormation con Lambda, SQS, DynamoDB, IAM, S3 e CloudWatch Logs.

Io ho usato un Mac con Apple silicon, macOS 27.2, Docker 29.9.3, AWS CLI 2.39.5 e la regione eu-south-1 (Milano). La regione si cambia con `AWS_REGION`. Su Linux e Windows non l'ho provato.

## Rifare la prova

```sh
./deploy.sh
./prova-una.sh diecimila 1000 10
```

`deploy.sh` compila il jar, crea il bucket del codice se non c'è e crea lo stack `tried-it-first-batch-fifo`. Gli event source mapping nascono spenti, e si accendono solo durante una corsa.

`prova-una.sh` vuole la Lambda (`dieci`, `diecimila`, `resto-del-conto`, `solo-il-fallito` o `eccezione`), il numero di conti e, tranne che per `dieci`, la finestra in secondi. I bonifici sono 10.000, o quanti ne dice la variabile `BONIFICI` (`BONIFICI=200 ./prova-una.sh dieci 1`), e ogni conto deve riceverne lo stesso numero, pari.

Per tutte le corse, che a me hanno preso circa 45 minuti:

```sh
./prova.sh
```

## Risultati

Le ho fatte il 27 novembre 2026 a Milano. Ogni file in `prove/` ha la configurazione della lettura, i numeri della corsa e le invocazioni partite ogni 10 secondi.

### Prova 1: 10.000 bonifici su 1.000 conti

Due corse per configurazione, i numeri sono prima corsa · seconda corsa.

| Lettura | Invocazioni | Batch più grande | Dalla prima all'ultima invocazione |
|---|---|---|---|
| batch da 10 | 1.004 · 1.017 | 10 · 10 | 19 · 18 s |
| batch da 10.000, finestra 1 s | 119 · 123 | 190 · 146 | 48 · 90 s |
| batch da 10.000, finestra 10 s | 65 · 50 | 568 · 803 | 95 · 88 s |
| batch da 10.000, finestra 60 s | 12 · 45 | 980 · 637 | 58 · 267 s |

In tutte le corse i bonifici registrati sono 10.000, nessuno rifiutato o scartato, e la coda dei falliti resta vuota. Nessun batch arriva a 10.000 messaggi: il più grande pesava 1 MB, lontano dai 6 MB di un'invocazione. Nella prima corsa con la finestra da 60 secondi la cronologia è questa:

```
Invocazioni partite, a blocchi di 10 secondi dalla prima
  dal secondo   invocazioni   messaggi
            0             2        630
           50            10      9.370
```

Dieci invocazioni quasi insieme, da circa 940 messaggi l'una, con al massimo 10 poller. La mia ipotesi è che ogni poller riempia il suo batch con la parte di coda che riesce a prendere; non ho modo di vederlo dall'esterno.

Le due corse con la stessa configurazione possono andare molto diversamente. Anche il tempo totale passato nella Lambda cambia parecchio da una corsa all'altra: con il batch da 10 sono 46 secondi nella prima e 10 nella seconda. Per questo non ne tiro fuori una media.

### Prova 2: 200 bonifici su un conto solo

| Lettura | Invocazioni | Batch più grande | Dalla prima all'ultima invocazione |
|---|---|---|---|
| batch da 10 | 20 | 10 | 3 s |
| batch da 10.000, finestra 10 s | 20 | 10 | 375 s |

In un batch arrivano al massimo 10 messaggi dello stesso message group, anche con il batch da 10.000. Ogni batch però aspetta la finestra, e la cronologia di `06-un-conto-batch-da-10000-finestra-10-corsa-1.txt` mostra un'invocazione ogni 20 secondi circa. La prima volta l'avevo lanciata con 10.000 bonifici sul conto: dopo i 30 minuti di attesa massima di `prova-una.sh` la coda era ancora quasi piena, per questo qui i bonifici sono 200.

### Prova 3: il bonifico 5 del conto numero 500 fallisce al primo tentativo

Batch da 10.000, finestra da 10 secondi, 10.000 bonifici su 1.000 conti.

| Lambda | Registrati | Rifiutati | Scartati come doppioni | Messaggi ricevuti di nuovo |
|---|---|---|---|---|
| `RimandaIlRestoDelConto` | 10.000 | 0 | 0 | 5 |
| `RimandaSoloIlFallito` | 9.998 | 1 | 1 | 1 |
| `LanciaUnEccezione` | 10.000 | 0 | 502 | 510 |

`RimandaSoloIlFallito` rimanda indietro l'accredito 5 e va avanti con l'addebito 6, che trova il saldo troppo basso e viene rifiutato. Quando l'accredito torna, dopo la visibility timeout, il conto ha già registrato il bonifico 10 e lo scarta come doppione:

```
Conti con bonifici rifiutati             1
  conto numero 500: 8 registrati, 1 rifiutati, 1 scartati
```

Non c'è nessuna eccezione e la coda dei falliti resta vuota, quindi niente che faccia scattare un allarme. `RimandaIlRestoDelConto` rimanda indietro i bonifici dal 5 al 10, come chiede la documentazione per le code FIFO, e li registra tutti al secondo giro. `LanciaUnEccezione` fa tornare in coda l'intero batch: i saldi tornano perché la Lambda scarta i bonifici con un numero già visto, ma 510 messaggi arrivano alla Lambda due volte.

## Quando conviene

Il batch da 10.000 riduce le invocazioni di un ordine di grandezza: da circa 1.000 a un numero tra 12 e 123 in queste corse. Conviene quando ogni invocazione ha un costo fisso alto, come una connessione al database da aprire, un'insert a blocchi o un'API che accetta liste. La Lambda di questa prova fa poco lavoro per invocazione, quindi nei tempi il vantaggio non si vede.

La coda non si svuota prima: ogni batch aspetta la finestra, e sui message group con tanti messaggi in fila la finestra va tenuta corta. Ho misurato solo lo svuotamento di una coda piena; con un traffico continuo e una finestra breve non l'ho provato.

Il bonifico perso della prova 3 non dipende dal batch da 10.000: anche un batch da 10 porta più bonifici dello stesso conto, e `RimandaSoloIlFallito` sbaglierebbe allo stesso modo. Quel caso a batch da 10 però non l'ho rifatto.

## Cose che ho trovato per strada

- `sam validate --lint` (SAM CLI 1.169.0) segnala `E3705` su `BatchSize: 10000` con una coda FIFO, perché non conosce ancora la novità. CloudFormation invece il template lo accetta.
- `aws cloudformation package` non legge il `CodeUri` dalla sezione `Globals` di SAM: senza `CodeUri` nella funzione zippa la cartella corrente e carica quella. Per questo nel template è ripetuto in ogni funzione.
- Su una coda FIFO la finestra (`MaximumBatchingWindowInSeconds`) si può usare solo in provisioned mode, e con un batch sopra i 10 messaggi deve essere almeno di 1 secondo.
- In un batch arrivano al massimo 10 messaggi dello stesso message group, ed è scritto nella documentazione di Lambda.
- La documentazione chiede una visibility timeout di almeno 6 volte il timeout della Lambda più la finestra: 190 secondi qui. Un messaggio rimandato indietro torna leggibile solo dopo quel tempo, per questo le corse con il bonifico che fallisce durano di più.
- Il mio account ha un limite di 10 esecuzioni Lambda contemporanee. Con un limite più alto i tempi, soprattutto quelli del batch da 10, possono cambiare.

## Costi

Si pagano le richieste a SQS, le letture e scritture su DynamoDB on-demand, il tempo delle Lambda e, in provisioned mode, i poller dell'event source mapping (Event Poller Unit) per tutto il tempo in cui la lettura è accesa: almeno 2 per mapping. Per questo `prova-una.sh` spegne la lettura alla fine di ogni corsa.

## Pulizia

```sh
./elimina.sh
```

Cancella lo stack, con code, Lambda, tabelle e log, e il bucket del codice. In locale restano l'immagine Docker di Maven e le dipendenze scaricate in `~/.m2`.

## Appendice: in locale con AWS Emulator

Per ora questa prova in locale non gira. Il 27 novembre ho provato con [AWS Emulator](https://docs.aws.amazon.com/emulator) 1.0.635, uscito quel giorno, e si ferma su due cose:

- **Le Lambda Java.** L'emulatore accetta una funzione `java21`, ma quando la chiami risponde `The runtime java21 is not supported by this emulator, which runs the Python and node runtimes.`
- **Il batch da 10.000 su una coda FIFO.** Sopra i 10 messaggi l'emulatore vuole una finestra maggiore di zero, ma su una coda FIFO la rifiuta con `Batching window is not supported for FIFO queues`. È la regola di prima del 20 novembre.

Per vedere se con una versione più nuova le cose sono cambiate:

```sh
./controlla-emulatore.sh
```

Lo script costruisce l'immagine dell'emulatore con l'ultima versione (il `Dockerfile` in `emulatore/` è quello della documentazione di AWS), lo avvia sulla porta 9297, prova a creare e chiamare una Lambda `java21` con `bonifici.jar` e a creare la lettura FIFO da 10.000 con la finestra. Alla fine ferma l'emulatore, e con lui sparisce tutto quello che ci aveva creato. Il risultato lo scrive anche in `prove/emulatore.txt`; il mio è quello sopra.

Due cose che servono se ci lavorate a mano: con l'emulatore DynamoDB risponde solo nella regione del suo file di configurazione (`us-east-1`, in `~/.aws/emulator/9297/config`), e il jar da 13 MB passato direttamente a `create-function` prende un 413, mentre caricato prima su S3 va bene. L'immagine `aws-emulator` resta in Docker, e si toglie con `docker image rm aws-emulator`.
