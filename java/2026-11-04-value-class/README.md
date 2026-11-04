# Integer e LocalDate senza identità, su JDK 28

In Java ogni oggetto ha un'identità: due `Long` che valgono 500 possono essere due oggetti diversi, e `==` guarda se sono lo stesso oggetto. È il motivo per cui `Long.valueOf(500) == Long.valueOf(500)` è `false`, mentre con 100 è `true` (fino a 127 i `Long` arrivano da una cache e sono sempre lo stesso oggetto).

Il JDK 28, che uscirà a marzo 2027, porta in preview le value class del progetto Valhalla ([JEP 401](https://openjdk.org/jeps/401)). Una value class non ha identità: `==` confronta i campi, e la JVM può tenere i valori direttamente dentro l'oggetto che li contiene invece di allocarne uno a parte. Con `--enable-preview` diventano value class anche `Integer`, `Long` e gli altri wrapper, `LocalDate` e quasi tutto `java.time`, `Optional`.

Volevo vedere cosa cambia per codice scritto oggi, senza toccarlo. Ho preso tre programmi che usano `Long`, `Integer` e `LocalDate` come si usano in un gestionale di pratiche e li ho lanciati sullo stesso JDK 28, senza e con `--enable-preview`. Al secondo ho affiancato la versione corretta:

| Programma | Cosa fa |
|---|---|
| `confronti/Confronti.java` | confronta con `==` due id pratica e due date di scadenza, e li usa come chiavi di un `IdentityHashMap` e di un `WeakHashMap` |
| `accrediti/Accrediti.java` | due thread fanno un milione di accrediti a testa, da un centesimo, sullo stesso conto, con un lock `synchronized (idConto)`; il numero del conto si passa come argomento |
| `accrediti/AccreditiCorretti.java` | lo stesso programma con il lock corretto: un oggetto di lock per ogni conto, preso da una `ConcurrentHashMap` |
| `rate/Rate.java` | tiene in memoria 1.200.000 rate, `record Rata(Long idPratica, LocalDate scadenza, Integer centesimi)`, e conta cosa c'è nell'heap |

## Contenuto

```
confronti/Confronti.java           == su Long e LocalDate, IdentityHashMap, WeakHashMap
accrediti/Accrediti.java           accrediti in parallelo con un lock sul numero del conto
accrediti/AccreditiCorretti.java   lo stesso, con un oggetto di lock per ogni conto
rate/Rate.java                     10.000 pratiche da 120 rate e l'istogramma dell'heap
prova.sh                           lancia tutto senza e con --enable-preview
prove/                             output delle mie esecuzioni
```

Niente Maven o Gradle: ogni file si lancia direttamente con `java`.

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (lo script è per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 2 GB bastano |
| Disco | circa 450 MB per il JDK |
| JDK | OpenJDK 28 early access, build 28-ea+22 del 30 ottobre 2026 |

Ho provato tutto su macOS 27, Apple M2 Pro. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Le build early access non sono fatte per la produzione e cambiano ogni settimana. Con una build successiva i numeri della memoria potrebbero essere diversi.

## 1. Scarica il JDK 28

Lo mettiamo in una cartella `~/jdk`, senza installarlo: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella.

macOS con Apple Silicon (M1, M2, ...):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/22/GPL/openjdk-28-ea+22_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28.jdk/Contents/Home/bin/java
```

Per i Mac Intel non c'è una build early access.

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/22/GPL/openjdk-28-ea+22_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28/bin/java
```

Windows: scaricate lo zip e scompattatelo dove volete, ad esempio in `C:\jdk`:

- https://download.java.net/java/early_access/jdk28/22/GPL/openjdk-28-ea+22_windows-x64_bin.zip

Quando esce una build nuova, quella vecchia di solito sparisce dal sito. Se il link risponde 404 prendete l'ultima da https://jdk.java.net/28/ e cambiate il numero della build nei comandi.

Verifica:

```sh
"$JAVA28" -version
```

Deve comparire `openjdk version "28-ea"` e `build 28-ea+22` (o un numero di build più alto).

## 2. Lancia le prove

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA28`:

```sh
./prova.sh
```

Ci mette una decina di secondi. Lancia `Confronti.java` e `Rate.java` due volte, senza e con `--enable-preview`, poi `Accrediti.java` una volta con il conto numero 100 e cinque volte con il numero 500, poi prova a lanciarlo con `--enable-preview`, e infine lancia `AccreditiCorretti.java` con il conto numero 500 cinque volte senza e cinque volte con la preview. Gli output completi vanno in `prove/`, e alla fine lo script stampa un riassunto.

Windows (PowerShell), dalla cartella di questo README:

```powershell
$JAVA28 = "C:\jdk\jdk-28\bin\java.exe"
foreach ($p in "confronti/Confronti.java", "rate/Rate.java") {
    & $JAVA28 $p
    & $JAVA28 --enable-preview $p
}
& $JAVA28 accrediti/Accrediti.java 100
& $JAVA28 accrediti/Accrediti.java 500
& $JAVA28 --enable-preview accrediti/Accrediti.java 500
& $JAVA28 accrediti/AccreditiCorretti.java 500
& $JAVA28 --enable-preview accrediti/AccreditiCorretti.java 500
```

## Risultato atteso

```
== prove/confronti.txt
pratica 100 == pratica 100: true
pratica 500 == pratica 500: false
scadenza == scadenza letta da testo: false
IdentityHashMap dopo due put con Long 500, chiavi: 2
WeakHashMap dopo un put con una LocalDate, chiavi: 1
== prove/confronti-preview.txt
pratica 100 == pratica 100: true
pratica 500 == pratica 500: true
scadenza == scadenza letta da testo: true
IdentityHashMap dopo due put con Long 500, chiavi: 1
WeakHashMap dopo un put con una LocalDate: java.lang.IdentityException: java.time.LocalDate is not an identity class
== prove/rate.txt
Total       4893797       95445632
== prove/rate-preview.txt
Total       2492106       68605848
== prove/accrediti.txt
conto 100: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 11.927,25 €, atteso 20.000,00 €
conto 500: saldo 12.287,46 €, atteso 20.000,00 €
conto 500: saldo 12.225,01 €, atteso 20.000,00 €
conto 500: saldo 10.792,58 €, atteso 20.000,00 €
conto 500: saldo 12.523,65 €, atteso 20.000,00 €
== prove/accrediti-preview.txt
accrediti/Accrediti.java:25: error: unexpected type
  required: identity class
  found:    Long
1 error
error: compilation failed
== prove/accrediti-corretti.txt
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
== prove/accrediti-corretti-preview.txt
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
conto 500: saldo 20.000,00 €, atteso 20.000,00 €
```

I saldi del conto numero 500 cambiano a ogni esecuzione, e i byte dell'heap di qualche decina di KB. Il resto deve essere uguale.

## Come è andata

### `==` confronta il valore

Senza preview, `==` tra due `Long` che valgono 500 è `false`, tra due che valgono 100 è `true`, e due `LocalDate` dello stesso giorno create in due modi diversi sono diverse. Con `--enable-preview` sono tutti `true`.

Il codice che confronta id o importi con `==` e che oggi funziona solo finché i numeri restano sotto 128, con le value class funzionerebbe sempre. Però va corretto comunque con `equals`: la preview si attiva solo con un'opzione, e sui JDK di oggi il risultato resta `false`.

Lo stesso vale nelle collezioni che lavorano per identità. In un `IdentityHashMap`, due `put` con due `Long` 500 creati separatamente danno due chiavi senza preview e una sola con la preview.

### Il lock sul numero del conto

```java
void accredita(Long idConto, long centesimi) {
    synchronized (idConto) {
        saldoInCentesimi += centesimi;
    }
}
```

Ogni accredito fa `Long.valueOf(contoDallaRichiesta)` partendo dal testo, come se il numero del conto arrivasse ogni volta da una richiesta. Due thread fanno un milione di accrediti a testa, da un centesimo, sullo stesso conto: 2.000.000 di accrediti, saldo atteso 20.000,00 €. Il numero del conto arriva come argomento: ho lanciato il programma con il conto 100 e con il conto 500, uno alla volta.

| Conto | Saldo |
|---|---|
| numero 100 | 20.000,00 € |
| numero 500, cinque esecuzioni | da 10.792,58 € a 12.523,65 € |

Con il conto numero 100 i due thread prendono il lock sullo stesso oggetto della cache. Con il 500 ogni accredito ha il suo `Long`, ogni thread si sincronizza su un oggetto diverso e il lock non blocca niente: nelle mie cinque esecuzioni si è perso tra il 37% e il 46% degli accrediti.

Il compilatore lo dice già, ma solo come warning:

```
accrediti/Accrediti.java:25: warning: [identity] attempt to synchronize on an instance of a value-based class
```

Con `--enable-preview` diventa un errore, perché un `Long` non ha più un'identità su cui sincronizzarsi:

```
accrediti/Accrediti.java:25: error: unexpected type
        synchronized (idConto) {
        ^
  required: identity class
  found:    Long
```

Se il lock arriva come `Object`, per esempio da un metodo generico, il codice compila e a runtime la JVM lancia `java.lang.IdentityException: Cannot synchronize on an instance of value class java.lang.Long`. Questo l'ho provato a parte, non è nello script.

La correzione è prendere il lock su un oggetto che appartiene al conto. In `AccreditiCorretti.java` cambia solo questo, rispetto ad `Accrediti.java`:

```java
private static final ConcurrentMap<Long, Object> LOCK_PER_CONTO = new ConcurrentHashMap<>();

void accredita(Long idConto, long centesimi) {
    synchronized (lockDelConto(idConto)) {
        saldoInCentesimi += centesimi;
    }
}

private static Object lockDelConto(Long idConto) {
    return LOCK_PER_CONTO.computeIfAbsent(idConto, id -> new Object());
}
```

| Conto numero 500 | JDK 28 senza preview | JDK 28 con `--enable-preview` |
|---|---|---|
| `Accrediti.java` | da 10.792,58 € a 12.523,65 € | non compila |
| `AccreditiCorretti.java` | 20.000,00 € in 5 esecuzioni su 5 | 20.000,00 € in 5 esecuzioni su 5 |

In un servizio vero il saldo starebbe nel database e il lock sarebbe lì, ma il problema del `synchronized` su un `Long` resta per qualunque altra cosa si protegga così.

### 1.200.000 rate: da 95,4 a 68,6 MB

`Rate.java` crea 10.000 pratiche da 120 rate ciascuna, le tiene in una `List<Rata>` e chiede alla JVM l'istogramma delle classi nell'heap (lo stesso di `jcmd <pid> GC.class_histogram`):

| Classe | Senza preview | Con `--enable-preview` |
|---|---|---|
| `Rata` | 1.200.000 oggetti, 28,8 MB | 1.200.000 oggetti, 38,4 MB |
| `Long` | 1.200.256 oggetti, 19,2 MB | 1.200.256 oggetti, 19,2 MB |
| `LocalDate` | 1.190.004 oggetti, 19,0 MB | 4 oggetti |
| `Integer` | 1.200.292 oggetti, 19,2 MB | 283 oggetti |
| Tutto l'heap | 95,4 MB | 68,6 MB |

Con la preview la scadenza e l'importo non sono più oggetti a parte: i loro valori stanno dentro la `Rata`, che passa da 24 a 32 byte, e spariscono 2,4 milioni di oggetti. L'heap scende del 28%, senza cambiare una riga di codice.

Il `Long` invece resta un oggetto a parte. La mia spiegazione, che viene dal JEP: un valore appiattito dentro un altro oggetto va letto e scritto tutto in una volta, e sull'hardware comune questo vuol dire circa 64 bit. Un `Long` ha già 64 bit di valore, più l'informazione "è null", e non ci sta. Un `Integer` (32 bit) e una `LocalDate` (anno, mese e giorno) sì.

Il JEP dice anche che la JVM applica queste ottimizzazioni quando può, e che nessuno le garantisce.

### Cosa si rompe

Senza identità alcune cose non si possono più fare:

- `synchronized` su un `Long`, un `Integer` o una `LocalDate`: errore di compilazione, o `IdentityException` a runtime se il riferimento è un `Object`
- `WeakHashMap` e `WeakReference` con una value class: `IdentityException`. Nella prova, `cache.put(scadenza, ...)` su un `WeakHashMap<LocalDate, String>` lancia `java.lang.IdentityException: java.time.LocalDate is not an identity class`
- `IdentityHashMap` funziona, ma due valori uguali sono la stessa chiave

Anche qui il compilatore avvisa già senza preview: sul `new WeakHashMap<>()` con chiave `LocalDate` stampa `warning: [identity] use of a value-based class with an operation that expects reliable identity`.

## Sui JDK di oggi

Senza `--enable-preview` il JDK 28 dà i risultati dei JDK che usiamo in produzione. Ho lanciato i programmi anche sul JDK 23 che avevo sul Mac: `==` tra due `Long` 500 è `false` e sul conto numero 500 mancano accrediti anche lì. Il 21 e il 25 non li ho provati. Sul 17 `Confronti.java` dà gli stessi risultati, mentre gli altri non compilano: usano `ExecutorService` in un try-with-resources e `List.getFirst()`, che ci sono dal 19 e dal 21.

Il warning sul `synchronized` lo stampa anche il 23, senza opzioni, con un altro nome:

```
accrediti/Accrediti.java:25: warning: [synchronization] attempt to synchronize on an instance of a value-based class
```

Cercare `[synchronization]` o `[identity]` nell'output della build dice già quali righe si romperanno con le value class.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` del JDK 28 nella variabile `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `Permission denied` su `prova.sh` | `chmod +x prova.sh`, oppure `sh prova.sh` |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 22 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |
| Con `--enable-preview` non cambia niente | State usando un `java` diverso da `JAVA28`, oppure una build vecchia del JDK 28: ho provato solo la 28-ea+22 |
| Su Windows, `confronti/Confronti.java` non trovato | Lanciate i comandi dalla cartella di questo README |

## Pulizia

Lo script non lascia niente fuori da `prove/`.

```sh
rm -rf ~/jdk
```

Su Windows cancellate `C:\jdk`.

## Link

- JEP 401, Value Classes and Objects (Preview): https://openjdk.org/jeps/401
- JDK 28 early access: https://jdk.java.net/28/
- JDK 28: https://openjdk.org/projects/jdk/28/
