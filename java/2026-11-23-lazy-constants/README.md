# 100 bonifici insieme e la tabella ABI caricata 100 volte: le Lazy Constants di Java 28

Java 28, che uscirà a marzo 2027, avrà le Lazy Constants in quarta preview ([JEP 547](https://openjdk.org/jeps/547)), con la stessa API del 27. L'interfaccia è `java.lang.LazyConstant`: tiene un valore che viene calcolato alla prima `get()`, una volta sola anche se la chiamano più thread insieme, e da lì non cambia più. Il JEP la presenta come la via di mezzo tra un campo `final`, che va valorizzato subito, e un campo normale caricato al primo uso, che però non è thread-safe e la JVM non può trattare come costante.

Volevo vederla su un caso che conosco: un servizio bonifici con tre componenti che leggono i loro dati dal DB.

| Componente | Caricamento | Chi lo usa |
|---|---|---|
| tabella ABI | 300 ms | tutti i bonifici, per trovare la banca del beneficiario dall'IBAN |
| tassi di cambio | 200 ms | solo i bonifici in valuta |
| template della ricevuta | 400 ms | solo chi chiede la ricevuta |

Il DB è finto: ogni caricamento aspetta con `Thread.sleep` e incrementa un contatore, così alla fine so quante volte è stato letto ogni componente.

Il servizio prende i componenti da un'interfaccia, `Componenti`, che ho scritto in sei modi:

| Variante | Classe | Come carica i componenti |
|---|---|---|
| `final` | `ConFinal` | campi `final` valorizzati nel costruttore |
| `if-null` | `ConIfNull` | al primo uso, con `if (campo == null)` |
| `double-checked` | `ConDoubleChecked` | al primo uso, con double-checked locking su un campo `volatile` |
| `holder` | `ConHolder` | al primo uso, con una holder class per componente che contiene un campo `static final` |
| `lazy-constant` | `ConLazyConstant` | al primo uso, con un campo `LazyConstant` per componente |
| `lazy-constant-retry` | `ConLazyConstantConRetry` | come `lazy-constant`, ma il `Supplier` riprova fino a 3 volte, con 100 ms di pausa, se il DB non risponde |

E cinque scenari, ognuno in una JVM nuova:

| Scenario | Cosa fa |
|---|---|
| `avvio` | avvia il servizio, manda un bonifico di 250,00 € e poi uno di 300,00 USD con la ricevuta |
| `concorrenza` | avvia il servizio e manda 100 bonifici di 250,00 € tutti insieme, ognuno su un thread virtuale |
| `db-giu` | il primo caricamento della tabella ABI va in timeout, poi il DB torna su; il programma manda 3 bonifici uno dopo l'altro |
| `db-giu-concorrenza` | come `db-giu`, ma con 100 bonifici tutti insieme |
| `db-giu-a-lungo` | come `db-giu`, ma il timeout dura per i primi 3 caricamenti, tanti quanti i tentativi del retry |

Ogni volta misura i millisecondi e conta i caricamenti dal DB.

## Il codice

Al primo uso con `if` (`bonifico/ConIfNull.java`):

```java
@Override
public TabellaAbi tabellaAbi() {
    if (tabellaAbi == null) {
        tabellaAbi = DB.caricaTabellaAbi();
    }
    return tabellaAbi;
}
```

Con double-checked locking (`bonifico/ConDoubleChecked.java`), per ognuno dei tre componenti:

```java
@Override
public TabellaAbi tabellaAbi() {
    var tabella = tabellaAbi;
    if (tabella == null) {
        synchronized (lockTabellaAbi) {
            tabella = tabellaAbi;
            if (tabella == null) {
                tabella = DB.caricaTabellaAbi();
                tabellaAbi = tabella;
            }
        }
    }
    return tabella;
}
```

Con `LazyConstant` (`bonifico/ConLazyConstant.java`):

```java
private final LazyConstant<TabellaAbi> tabellaAbi =
        LazyConstant.of(DB::caricaTabellaAbi);

@Override
public TabellaAbi tabellaAbi() {
    return tabellaAbi.get();
}
```

`LazyConstant.of` riceve un `Supplier` e non lo chiama subito. Lo chiama la prima `get()`; se nel frattempo arrivano altri thread, aspettano che finisca e poi leggono lo stesso valore.

Con il retry nel `Supplier` (`bonifico/ConLazyConstantConRetry.java` e `bonifico/Retry.java`):

```java
private static final Retry RETRY = new Retry(3, Duration.ofMillis(100));

private final LazyConstant<TabellaAbi> tabellaAbi =
        LazyConstant.of(() -> RETRY.esegui(DB::caricaTabellaAbi));
```

```java
<T> T esegui(Supplier<T> caricamento) {
    for (var tentativo = 1; ; tentativo++) {
        try {
            return caricamento.get();
        } catch (DatabaseNonDisponibile e) {
            if (tentativo == tentativi) {
                throw e;
            }
            aspettaPrimaDiRiprovare(e);
        }
    }
}
```

Riprova solo su `DatabaseNonDisponibile`: un errore di programmazione non ha senso ripeterlo.

## Contenuto

```
bonifico/Prova.java                   lancia una variante in uno scenario e stampa i risultati
bonifico/ConFinal.java                le sei varianti di Componenti
bonifico/ConIfNull.java
bonifico/ConDoubleChecked.java
bonifico/ConHolder.java
bonifico/ConLazyConstant.java
bonifico/ConLazyConstantConRetry.java
bonifico/Retry.java                   il retry usato da ConLazyConstantConRetry
bonifico/Componenti.java              l'interfaccia comune alle varianti
bonifico/ServizioBonifici.java        il servizio che usa i componenti
bonifico/Database.java                il DB finto, con i contatori e il timeout simulato
bonifico/Componente.java              nomi e tempi di caricamento dei tre componenti
bonifico/TabellaAbi.java              i tre componenti
bonifico/TassiDiCambio.java
bonifico/TemplateRicevuta.java
bonifico/Variante.java                nomi e descrizioni delle varianti
bonifico/Scenario.java                nomi degli scenari
bonifico/...                          record, eccezione e formattazione degli importi
prova.sh                              compila e lancia tutte le varianti in tutti gli scenari, salva gli output in prove/
prove/                                output delle mie esecuzioni
```

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (lo script è per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 1 GB basta |
| Disco | circa 450 MB per il JDK |
| JDK | OpenJDK 28 early access, build 28-ea+25 del 19 novembre 2026 |
| Altro | `curl` (su macOS c'è già) |

Ho provato tutto su macOS 27, Apple M2 Pro. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Le build early access non sono fatte per la produzione e cambiano ogni settimana.

## 1. Scarica il JDK 28

Lo mettiamo in una cartella `~/jdk`, senza installarlo: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella.

macOS con Apple Silicon (M1, M2, ...):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/25/GPL/openjdk-28-ea+25_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28.jdk/Contents/Home/bin/java
```

Per i Mac Intel non c'è una build early access.

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/25/GPL/openjdk-28-ea+25_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28/bin/java
```

Windows: scaricate lo zip e scompattatelo dove volete, ad esempio in `C:\jdk`:

- https://download.java.net/java/early_access/jdk28/25/GPL/openjdk-28-ea+25_windows-x64_bin.zip

Quando esce una build nuova, quella vecchia di solito sparisce dal sito. Se il link risponde 404 prendete l'ultima da https://jdk.java.net/28/ e cambiate il numero della build nei comandi.

Verifica:

```sh
"$JAVA28" -version
```

Deve comparire `openjdk version "28-ea"` e `build 28-ea+25` (o un numero di build più alto).

## 2. Lancia la prova

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA28`:

```sh
./prova.sh
```

Lo script compila le classi con il `javac` dello stesso JDK in `target/classes`, poi lancia le sei varianti nei cinque scenari: 30 JVM, una dopo l'altra, in circa 21 secondi. Stampa gli output e li salva in `prove/`. All'inizio `javac` scrive `Note: Some input files use preview features of Java SE 28.`: è normale, `LazyConstant` è in preview.

Compilo prima con `javac` invece di lanciare `java bonifico/Prova.java`: con il launcher dei sorgenti le classi vengono compilate mentre il programma gira, e quei millisecondi finivano nei tempi misurati (il servizio con i campi `final` risultava pronto dopo 1.052 ms invece di circa 900).

Una variante in uno scenario, dopo che lo script ha compilato:

```sh
"$JAVA28" --enable-preview -cp target/classes bonifico.Prova lazy-constant db-giu
```

Windows (PowerShell):

```powershell
$JAVA28 = "C:\jdk\jdk-28\bin\java.exe"
$JAVAC28 = "C:\jdk\jdk-28\bin\javac.exe"
& $JAVAC28 --release 28 --enable-preview -Xlint:-preview -d target/classes (Get-ChildItem bonifico/*.java).FullName
foreach ($v in "final", "if-null", "double-checked", "holder", "lazy-constant", "lazy-constant-retry") {
    foreach ($s in "avvio", "concorrenza", "db-giu", "db-giu-concorrenza", "db-giu-a-lungo") {
        & $JAVA28 --enable-preview -cp target/classes bonifico.Prova $v $s
    }
}
```

## Risultato atteso

Il mio `prove/if-null-concorrenza.txt`:

```
Java 28-ea+25-2095, scenario concorrenza
if-null: caricamento al primo uso con if (campo == null)
servizio pronto dopo 0 ms
100 bonifici di 250,00 € insieme
  eseguiti: 100, risposte tra 307 e 309 ms
  caricamenti dal DB: tabella ABI 100, tassi di cambio 0, template della ricevuta 0
```

`prove/lazy-constant-db-giu.txt`:

```
Java 28-ea+25-2095, scenario db-giu
lazy-constant: un campo LazyConstant per componente
il DB va in timeout al primo caricamento della tabella ABI, poi torna su
servizio pronto dopo 1 ms
bonifico 1: errore dopo 306 ms, NoSuchElementException (Unable to access the constant because bonifico.DatabaseNonDisponibile was thrown at initial computation), causa: DatabaseNonDisponibile
bonifico 2: errore dopo 0 ms, NoSuchElementException (Unable to access the constant because bonifico.DatabaseNonDisponibile was thrown at initial computation)
bonifico 3: errore dopo 0 ms, NoSuchElementException (Unable to access the constant because bonifico.DatabaseNonDisponibile was thrown at initial computation)
  caricamenti dal DB: tabella ABI 1, tassi di cambio 0, template della ricevuta 0
```

e `prove/lazy-constant-retry-db-giu-concorrenza.txt`:

```
Java 28-ea+25-2095, scenario db-giu-concorrenza
lazy-constant-retry: LazyConstant con 3 tentativi nel Supplier
il DB va in timeout al primo caricamento della tabella ABI, poi torna su
servizio pronto dopo 1 ms
100 bonifici di 250,00 € insieme
  eseguiti: 100, risposte tra 713 e 716 ms
  caricamenti dal DB: tabella ABI 2, tassi di cambio 0, template della ricevuta 0
```

I millisecondi cambiano di qualche unità a ogni giro, i caricamenti e gli errori devono essere gli stessi. Gli altri 27 output sono in `prove/`.

## Come è andata

### Avvio

| Variante | Servizio pronto dopo | Bonifico di 250,00 € | Bonifico di 300,00 USD con ricevuta |
|---|---|---|---|
| `final` | 916 ms | 14 ms | 0 ms |
| `if-null` | 0 ms | 324 ms | 611 ms |
| `double-checked` | 0 ms | 327 ms | 612 ms |
| `holder` | 0 ms | 325 ms | 609 ms |
| `lazy-constant` | 1 ms | 322 ms | 609 ms |
| `lazy-constant-retry` | 1 ms | 329 ms | 611 ms |

Con i campi `final` il costruttore carica tutti e tre i componenti, e il servizio è pronto dopo 916 ms anche se il primo bonifico usa solo la tabella ABI. Con le altre varianti il servizio parte subito e il primo bonifico paga i 300 ms della tabella ABI. I 600 ms di tassi e template li paga poi il bonifico in dollari con la ricevuta.

Il caricamento quindi passa dall'avvio alla prima richiesta che usa quel componente. Conviene quando una parte dei componenti serve solo ad alcune richieste, o a nessuna.

### 100 bonifici insieme

| Variante | Eseguiti | Risposte tra | Caricamenti della tabella ABI |
|---|---|---|---|
| `final` | 100 | 0 e 0 ms (dopo 916 ms di avvio) | 1 |
| `if-null` | 100 | 307 e 309 ms | **100** |
| `double-checked` | 100 | 308 e 311 ms | 1 |
| `holder` | 100 | 304 e 307 ms | 1 |
| `lazy-constant` | 100 | 307 e 309 ms | 1 |
| `lazy-constant-retry` | 100 | 304 e 306 ms | 1 |

Con `if (campo == null)` tutti e 100 i thread trovano il campo vuoto, perché il primo caricamento dura 300 ms, e ognuno si carica la sua tabella: 100 letture dal DB per lo stesso dato. Ho rilanciato questo scenario altre 5 volte e i caricamenti sono stati sempre 100. Qui il danno è un DB martellato all'avvio; con un componente che apre connessioni o file sarebbero 100 pool o 100 file.

Con double-checked locking, holder e `LazyConstant` la tabella viene caricata una volta sola e gli altri 99 thread aspettano quella.

### Il DB giù al primo caricamento, un bonifico alla volta

| Variante | Bonifico 1 | Bonifico 2 | Bonifico 3 | Caricamenti della tabella ABI |
|---|---|---|---|---|
| `final` | il servizio non parte | | | 1 |
| `if-null` | `DatabaseNonDisponibile` | eseguito | eseguito | 2 |
| `double-checked` | `DatabaseNonDisponibile` | eseguito | eseguito | 2 |
| `holder` | `ExceptionInInitializerError` | `NoClassDefFoundError` | `NoClassDefFoundError` | 1 |
| `lazy-constant` | `NoSuchElementException` | `NoSuchElementException` | `NoSuchElementException` | 1 |
| `lazy-constant-retry` | eseguito dopo 712 ms | eseguito | eseguito | 2 |

Questo è il risultato che non mi aspettavo prima di leggere il javadoc. Se il `Supplier` lancia un'eccezione, la `LazyConstant` non resta vuota: va in uno stato di errore definitivo. Da lì ogni `get()` lancia `NoSuchElementException` senza richiamare il `Supplier`, e infatti i caricamenti della tabella ABI sono 1 anche se i bonifici sono 3. Il DB è tornato su, ma il componente resta rotto fino al riavvio dell'applicazione.

La holder class fa lo stesso per un motivo diverso: se l'inizializzazione di una classe fallisce, la JVM la segna come fallita e ogni accesso successivo lancia `NoClassDefFoundError`.

Con `if-null` e double-checked locking il campo resta `null` dopo l'errore, quindi il bonifico dopo riprova e passa. Con i campi `final` il servizio non parte proprio, e in un container con un health check l'orchestratore lo riavvia: all'avvio spesso è proprio quello che si vuole.

Con il retry nel `Supplier` il primo bonifico passa dopo 712 ms: 300 ms di timeout, 100 ms di pausa, 300 ms del secondo caricamento e qualche millisecondo di JVM.

### Il DB giù al primo caricamento, 100 bonifici insieme

| Variante | Eseguiti | In errore | Caricamenti della tabella ABI |
|---|---|---|---|
| `final` | il servizio non parte | | 1 |
| `if-null` | 99, tra 305 e 307 ms | 1, `DatabaseNonDisponibile` | 100 |
| `double-checked` | 99, tra 608 e 610 ms | 1, `DatabaseNonDisponibile` | 2 |
| `holder` | 0 | 1 `ExceptionInInitializerError`, 99 `NoClassDefFoundError` | 1 |
| `lazy-constant` | 0 | 100 `NoSuchElementException` | 1 |
| `lazy-constant-retry` | **100, tra 713 e 716 ms** | 0 | 2 |

Con `LazyConstant` senza retry sbagliano tutti e 100: il thread che ha fatto il caricamento prende l'eccezione con la causa, gli altri 99 erano in attesa e la ricevono senza causa. Con la holder class è uguale: chi aspettava l'inizializzazione della classe prende `NoClassDefFoundError`.

Con il retry nel `Supplier` passano tutti, ma tutti aspettano il retry: le risposte arrivano tra 713 e 716 ms, perché il javadoc garantisce che mentre un thread esegue il `Supplier` gli altri restano fermi finché non ha finito. Con il double-checked locking succede l'opposto: un bonifico prende l'errore dopo 305 ms, il thread dopo entra nel blocco `synchronized`, trova il campo ancora `null` e ricarica, e gli altri 98 aspettano quel secondo caricamento.

### Il DB giù per 3 caricamenti di fila

| Variante | Bonifico 1 | Bonifico 2 | Bonifico 3 | Caricamenti della tabella ABI |
|---|---|---|---|---|
| `final` | il servizio non parte | | | 1 |
| `if-null` | `DatabaseNonDisponibile` | `DatabaseNonDisponibile` | `DatabaseNonDisponibile` | 3 |
| `double-checked` | `DatabaseNonDisponibile` | `DatabaseNonDisponibile` | `DatabaseNonDisponibile` | 3 |
| `holder` | `ExceptionInInitializerError` | `NoClassDefFoundError` | `NoClassDefFoundError` | 1 |
| `lazy-constant` | `NoSuchElementException` | `NoSuchElementException` | `NoSuchElementException` | 1 |
| `lazy-constant-retry` | `NoSuchElementException` dopo 1.125 ms | `NoSuchElementException` | `NoSuchElementException` | 3 |

Il retry dentro il `Supplier` va bene per un errore di un attimo, non per un DB giù a lungo. Qui il primo bonifico usa tutti e 3 i tentativi (3 caricamenti da 300 ms e 2 pause da 100 ms) e prende l'errore dopo 1.125 ms; da lì la `LazyConstant` è in errore definitivo come quella senza retry, e i bonifici dopo falliscono subito senza un quarto caricamento. Con `if-null` e double-checked locking ogni bonifico ricarica la tabella da capo: 3 bonifici, 3 caricamenti, 3 errori.

I tentativi vanno comunque limitati: `get()` non ha un timeout, e con 100 bonifici insieme tutti i thread restano fermi finché il `Supplier` non ha finito.

## Cosa non ho misurato

Il JEP dice che la JVM può trattare il contenuto di una `LazyConstant` come una costante vera e ottimizzare le letture come fa con un `static final` (constant folding). Vale però solo se si arriva alla `LazyConstant` da un campo `static final` passando solo per campi fidati: altri `static final`, campi dei record, campi final delle hidden class. I campi final di istanza di una classe normale, come quelli di `ConLazyConstant`, non sono fidati, perché la reflection può ancora modificarli. Per misurare la differenza servirebbe JMH, e non l'ho fatto: in questa prova i tempi sono tutti dominati dai 300 ms del DB.

La holder class funziona solo con campi `static`: in `ConHolder` la tabella è una per tutta la JVM, anche se si creano più istanze di `ConHolder`. `LazyConstant` va bene anche come campo di istanza, come in `ConLazyConstant`.

## Sui JDK di oggi

L'API è in preview da Java 25, e serve `--enable-preview` sia per compilare sia per lanciare:

| JDK | JEP | Cosa cambia |
|---|---|---|
| 25 | [502](https://openjdk.org/jeps/502) | prima preview, con il nome `StableValue` e metodi di basso livello come `orElseSet` e `trySet` |
| 26 | [526](https://openjdk.org/jeps/526) | diventa `LazyConstant`, restano solo i metodi che ricevono il `Supplier`, il `null` non è più ammesso, `List.ofLazy` e `Map.ofLazy` |
| 27 | [531](https://openjdk.org/jeps/531) | tolti `isInitialized()` e `orElse()`, aggiunto `Set.ofLazy` |
| 28 | [547](https://openjdk.org/jeps/547) | nessun cambiamento |

Questo codice l'ho fatto girare solo sulla build early access del 28. Con il 25 non compila, perché `LazyConstant` lì non esiste. Con il 26 e il 27 non l'ho provato.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` del JDK 28 nella variabile `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `LazyConstant is a preview API and is disabled by default` | Manca `--enable-preview` a `javac` |
| `Preview features are not enabled for bonifico/Prova` | Manca `--enable-preview` a `java` |
| `cannot find symbol` su `LazyConstant` | State compilando con un JDK più vecchio del 26 |
| VS Code o IntelliJ segnalano `LazyConstant` in rosso | L'IDE usa un JDK più vecchio o non ha la preview attiva: impostate il JDK 28 con le preview per il progetto. Da riga di comando con `prova.sh` va tutto bene |
| `Uso: java --enable-preview -cp target/classes bonifico.Prova <...>` | Il nome della variante o dello scenario è sbagliato: devono essere tra quelli elencati nel messaggio |
| `Permission denied` su `prova.sh` | `chmod +x prova.sh`, oppure `sh prova.sh` |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 25 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |

## Pulizia

```sh
rm -rf ~/jdk target
```

Su Windows cancellate `C:\jdk` e la cartella `target`.

## Link

- JEP 547, Lazy Constants (Fourth Preview): https://openjdk.org/jeps/547
- JDK 28 early access: https://jdk.java.net/28/
- Javadoc di `LazyConstant` nella build early access: https://download.java.net/java/early_access/jdk28/docs/api/java.base/java/lang/LazyConstant.html
