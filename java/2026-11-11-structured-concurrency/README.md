# 1.000 bonifici rifiutati e 1.000 controlli ancora in corso: ExecutorService e StructuredTaskScope in Java 28

Java 28, che uscirà a marzo 2027, avrà la Structured Concurrency come API definitiva ([JEP 543](https://openjdk.org/jeps/543)). È in preview da Java 21, dopo due giri in incubazione su Java 19 e 20, e con il 28 esce dalla preview così com'era nel 27. La classe è `StructuredTaskScope`, in `java.util.concurrent`: fa partire più operazioni in parallelo, ognuna in un thread virtuale, e le gestisce come un blocco solo. Se una fallisce le altre vengono interrotte, e dal `try` non si esce finché i loro thread non sono finiti.

Volevo vedere cosa cambia rispetto a `ExecutorService` su un caso che conosco. Prima di eseguire un bonifico un servizio fa tre controlli, e siccome non dipendono l'uno dall'altro li fa in parallelo:

| Controllo | Quanto ci mette | Cosa fa nella prova |
|---|---|---|
| saldo del conto | 200 ms | restituisce 10.000,00 € |
| antifrode | 100 ms | rifiuta i bonifici da 5.000 € in su |
| limite giornaliero | 2 secondi | toglie dal limite i movimenti del giorno, letti in 20 pagine da 100 ms |

I controlli sono finti: aspettano con `Thread.sleep` e restituiscono valori fissi. `Thread.sleep` esce subito con un'`InterruptedException` se il thread viene interrotto.

Il programma prova cinque modi di scrivere le verifiche:

| Variante | Classe e metodo | Come aspetta i tre controlli |
|---|---|---|
| `executor` | `ConExecutor.verifica` | `ExecutorService` condiviso, `get()` su saldo, limite e antifrode, in quest'ordine |
| `executor-antifrode-prima` | `ConExecutor.verificaAntifrodePrima` | lo stesso `ExecutorService` condiviso, ma il primo `get()` è sull'antifrode |
| `executor-per-richiesta` | `ConExecutorPerRichiesta.verifica` | un `ExecutorService` nuovo per ogni bonifico, in un try-with-resources, primo `get()` sull'antifrode |
| `scope` | `ConScope.verifica` | `StructuredTaskScope` |
| `scope-interruzione-ignorata` | `ConScope.verifica` | `StructuredTaskScope`, ma il controllo del limite ignora l'interruzione |

Tutti gli `ExecutorService` della prova sono `Executors.newVirtualThreadPerTaskExecutor()`, cioè un thread virtuale per ogni operazione, come lo scope. Cambia solo il modo di aspettare i risultati.

Per ogni variante il programma manda:

1. un bonifico da 250,00 €, che passa tutti i controlli;
2. un bonifico da 5.000,00 €, che l'antifrode rifiuta;
3. 1.000 bonifici da 5.000,00 € tutti insieme.

Ogni volta misura dopo quanti millisecondi arriva la risposta e conta i controlli del limite finiti, interrotti e ancora in corso: subito dopo la risposta e di nuovo 3 secondi dopo.

## Il codice

Con l'`ExecutorService` condiviso (`bonifico/ConExecutor.java`):

```java
Verifiche verifica(Bonifico bonifico) throws ExecutionException, InterruptedException {
    var saldo = esecutore.submit(controlli::saldo);
    var limite = esecutore.submit(controlli::limiteResiduo);
    var antifrode = esecutore.submit(() -> controlli.rischio(bonifico));
    return new Verifiche(saldo.get(), limite.get(), antifrode.get());
}
```

Con `StructuredTaskScope` (`bonifico/ConScope.java`):

```java
Verifiche verifica(Bonifico bonifico) throws ExecutionException, InterruptedException {
    try (var scope = StructuredTaskScope.open()) {
        var saldo = scope.fork(controlli::saldo);
        var limite = scope.fork(controlli::limiteResiduo);
        var antifrode = scope.fork(() -> controlli.rischio(bonifico));
        scope.join();
        return new Verifiche(saldo.get(), limite.get(), antifrode.get());
    }
}
```

`StructuredTaskScope.open()` senza argomenti fallisce appena fallisce uno dei tre controlli: `join()` lancia una `ExecutionException` con l'eccezione del controllo come causa. Se va tutto bene, dopo `join()` i tre `get()` restituiscono i risultati senza aspettare.

## Contenuto

```
bonifico/Prova.java                    lancia una variante e stampa i risultati
bonifico/ConExecutor.java              le due varianti con l'ExecutorService condiviso
bonifico/ConExecutorPerRichiesta.java  la variante con un ExecutorService per ogni bonifico
bonifico/ConScope.java                 la variante con StructuredTaskScope
bonifico/Controlli.java                i tre controlli finti e i contatori del limite giornaliero
bonifico/Variante.java                 i nomi delle varianti e la classe che usa ognuna
bonifico/Bonifico.java                 il bonifico (IBAN e importo)
bonifico/Verifiche.java                i risultati dei tre controlli
bonifico/Verificatore.java             l'interfaccia comune alle varianti
bonifico/BonificoRifiutato.java        l'eccezione dell'antifrode
prova.sh                               lancia tutte le varianti e salva gli output in prove/
prove/                                 output delle mie esecuzioni
```

Niente Maven o Gradle: `java bonifico/Prova.java` compila al volo anche le altre classi della cartella (si può da Java 22, [JEP 458](https://openjdk.org/jeps/458)).

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (lo script è per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 1 GB basta |
| Disco | circa 450 MB per il JDK |
| JDK | OpenJDK 28 early access, build 28-ea+23 del 5 novembre 2026 |
| Altro | `curl` (su macOS c'è già) |

Ho provato tutto su macOS 27, Apple M2 Pro. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Le build early access non sono fatte per la produzione e cambiano ogni settimana.

## 1. Scarica il JDK 28

Lo mettiamo in una cartella `~/jdk`, senza installarlo: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella.

macOS con Apple Silicon (M1, M2, ...):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/23/GPL/openjdk-28-ea+23_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28.jdk/Contents/Home/bin/java
```

Per i Mac Intel non c'è una build early access.

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/23/GPL/openjdk-28-ea+23_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28/bin/java
```

Windows: scaricate lo zip e scompattatelo dove volete, ad esempio in `C:\jdk`:

- https://download.java.net/java/early_access/jdk28/23/GPL/openjdk-28-ea+23_windows-x64_bin.zip

Quando esce una build nuova, quella vecchia di solito sparisce dal sito. Se il link risponde 404 prendete l'ultima da https://jdk.java.net/28/ e cambiate il numero della build nei comandi.

Verifica:

```sh
"$JAVA28" -version
```

Deve comparire `openjdk version "28-ea"` e `build 28-ea+23` (o un numero di build più alto).

## 2. Lancia la prova

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA28`:

```sh
./prova.sh
```

Ci mette poco più di un minuto, perché dopo ogni risposta il programma aspetta 3 secondi per vedere cosa fanno i controlli rimasti in giro. Lancia le cinque varianti una dopo l'altra, stampa gli output e li salva in `prove/`.

Una variante sola:

```sh
"$JAVA28" bonifico/Prova.java scope
```

Windows (PowerShell):

```powershell
$JAVA28 = "C:\jdk\jdk-28\bin\java.exe"
foreach ($v in "executor", "executor-antifrode-prima", "executor-per-richiesta", "scope", "scope-interruzione-ignorata") {
    & $JAVA28 bonifico/Prova.java $v
}
```

## Risultato atteso

Il mio `prove/executor-antifrode-prima.txt`:

```
Java 28-ea+23-1886, executor-antifrode-prima: ExecutorService condiviso, get() prima sull'antifrode
bonifico da 250,00 €: autorizzato dopo 2.077 ms
  limite giornaliero alla risposta:  finiti 1, interrotti 0, in corso 0
  limite giornaliero 3 secondi dopo: finiti 1, interrotti 0, in corso 0
bonifico da 5.000,00 €: rifiutato dopo 103 ms (antifrode: bonifico sospetto)
  limite giornaliero alla risposta:  finiti 0, interrotti 0, in corso 1
  limite giornaliero 3 secondi dopo: finiti 1, interrotti 0, in corso 0
1.000 bonifici da 5.000,00 € insieme: 1.000 rifiutati, risposte tra 100 e 104 ms
  limite giornaliero alla risposta:  finiti 0, interrotti 0, in corso 1.000
  limite giornaliero 3 secondi dopo: finiti 1.000, interrotti 0, in corso 0
```

e `prove/scope.txt`:

```
Java 28-ea+23-1886, scope: StructuredTaskScope
bonifico da 250,00 €: autorizzato dopo 2.062 ms
  limite giornaliero alla risposta:  finiti 1, interrotti 0, in corso 0
  limite giornaliero 3 secondi dopo: finiti 1, interrotti 0, in corso 0
bonifico da 5.000,00 €: rifiutato dopo 105 ms (antifrode: bonifico sospetto)
  limite giornaliero alla risposta:  finiti 0, interrotti 1, in corso 0
  limite giornaliero 3 secondi dopo: finiti 0, interrotti 1, in corso 0
1.000 bonifici da 5.000,00 € insieme: 1.000 rifiutati, risposte tra 106 e 118 ms
  limite giornaliero alla risposta:  finiti 0, interrotti 1.000, in corso 0
  limite giornaliero 3 secondi dopo: finiti 0, interrotti 1.000, in corso 0
```

I millisecondi cambiano di qualche unità a ogni giro, i conteggi devono essere gli stessi. Gli altri tre output sono in `prove/`.

## Come è andata

| Variante | Bonifico da 5.000 €, risposta dopo | 1.000 bonifici, risposte tra | Controlli del limite alla risposta, su 1.000 |
|---|---|---|---|
| `executor` | 2.072 ms | 2.044 e 2.068 ms | 1.000 finiti |
| `executor-antifrode-prima` | 103 ms | 100 e 104 ms | **1.000 in corso** |
| `executor-per-richiesta` | 2.057 ms | 2.069 e 2.090 ms | 1.000 finiti |
| `scope` | 105 ms | 106 e 118 ms | 1.000 interrotti |
| `scope-interruzione-ignorata` | 1.989 ms | 1.965 e 2.077 ms | 1.000 finiti, 1.000 interruzioni ignorate |

Il bonifico da 250,00 € è stato autorizzato in tutte le varianti dopo circa 2 secondi (tra 2.062 e 2.079 ms): servono tutti e tre i risultati, quindi si aspetta il controllo più lento. Quando va tutto bene lo scope non cambia i tempi.

### Con l'ExecutorService condiviso

Con i `get()` nell'ordine dei `submit`, il rifiuto dell'antifrode, arrivato dopo 100 ms, viene fuori solo quando il limite ha finito: risposta dopo 2.072 ms.

Con il primo `get()` sull'antifrode la risposta arriva dopo 103 ms, ma il controllo del limite resta in corso. Con 1.000 bonifici, quando è partita l'ultima risposta i controlli del limite in corso erano 1.000, e nei 2 secondi dopo sono finiti tutti, per bonifici già rifiutati. Nessuno li ha fermati: il `get()` che lancia l'eccezione non sa niente degli altri `Future`.

Si possono fermare a mano, chiamando `cancel(true)` sugli altri `Future` quando uno fallisce, ma va scritto in ogni metodo che lavora così. Nella prova non l'ho messo.

### Con un ExecutorService per bonifico

Da Java 19 `ExecutorService` si può chiudere con try-with-resources, e `close()` aspetta che finiscano tutte le operazioni avviate. Così non resta niente in corso, ma il rifiuto arriva dopo 2.057 ms anche con il primo `get()` sull'antifrode: l'eccezione esce dal `try` solo dopo `close()`, che aspetta il limite.

### Con StructuredTaskScope

Rifiuto dopo 105 ms e controllo del limite interrotto, anche con 1.000 bonifici insieme (1.000 interrotti su 1.000). Quando l'antifrode lancia l'eccezione lo scope interrompe gli altri thread, `join()` lancia `ExecutionException` e `close()` aspetta che i thread interrotti escano, cosa che con `Thread.sleep` succede subito.

### Quando l'interruzione viene ignorata

Lo scope ferma gli altri controlli interrompendo i loro thread. Se un controllo cattura l'`InterruptedException` e va avanti, il suo thread non si ferma e `close()` lo aspetta. Nella variante `scope-interruzione-ignorata` è `leggiPaginaMovimenti` in `Controlli.java` a farlo:

```java
try {
    Thread.sleep(DURATA_PAGINA_MOVIMENTI);
} catch (InterruptedException e) {
    if (!limiteIgnoraInterruzione) {
        throw e;
    }
    interruzioniIgnorate.incrementAndGet();
}
```

La risposta arriva dopo 1.989 ms, come con un `ExecutorService` per bonifico. È poco meno di 2 secondi perché la pagina che si stava leggendo al momento dell'interruzione si ferma prima, poi il ciclo passa alla pagina dopo. Il JEP lo scrive: i controlli devono finire il prima possibile quando vengono interrotti, altrimenti la chiusura dello scope si allunga.

Nel codice vero un `catch (InterruptedException e)` che scrive un log e va avanti è facile da trovare, e SonarQube lo segnala con la regola S2142. Se poi l'interruzione fermi davvero una query JDBC o una chiamata HTTP dipende dal driver e dal client: io ho provato solo con `Thread.sleep`.

## Sui JDK di oggi

Da Java 21 a Java 27 la Structured Concurrency è in preview: serve `--enable-preview`, e l'API è cambiata più volte. Fino al 24 lo scope si creava con `new StructuredTaskScope.ShutdownOnFailure()`, dal 25 con `StructuredTaskScope.open()`, e nel 27 il tipo dell'eccezione lanciata da `join()` è diventato un parametro di tipo dello scope.

Questo codice l'ho fatto girare solo sulla build early access del 28. Con il JDK 23 non compila: oltre all'errore sulla preview, `open()` lì non esiste.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` del JDK 28 nella variabile `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `StructuredTaskScope is a preview API and is disabled by default` e `cannot find symbol ... method open()` | State usando un JDK più vecchio del 28 (a me è successo con il 23) |
| VS Code o IntelliJ segnalano `StructuredTaskScope` come API in preview | L'IDE usa un JDK più vecchio del 28: impostate il JDK 28 per il progetto. Da riga di comando con `$JAVA28` va tutto bene |
| `Uso: java bonifico/Prova.java <...>` | Il nome della variante è sbagliato: deve essere uno di quelli elencati nel messaggio |
| `Permission denied` su `prova.sh` | `chmod +x prova.sh`, oppure `sh prova.sh` |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 23 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |

## Pulizia

```sh
rm -rf ~/jdk
```

Su Windows cancellate `C:\jdk`.

## Link

- JEP 543, Structured Concurrency: https://openjdk.org/jeps/543
- JDK 28 early access: https://jdk.java.net/28/
- JEP 458, Launch Multi-File Source-Code Programs: https://openjdk.org/jeps/458
- SonarQube, regola S2142: https://rules.sonarsource.com/java/RSPEC-2142/
