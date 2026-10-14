# Un milione di rate su JDK 21 e su JDK 27

Con JDK 27, uscito il 15 settembre 2026, gli header compatti degli oggetti sono attivi di default ([JEP 534](https://openjdk.org/jeps/534)). Ogni oggetto in memoria ha un'intestazione, e sulle JVM a 64 bit passa da 12 a 8 byte.

4 byte sembrano niente. Ho voluto vedere quanto pesano su qualcosa che conosco: il piano di ammortamento di 10.000 pratiche di cessione del quinto, 120 rate ciascuna, 1.200.000 oggetti `Rata` con dentro `BigDecimal` e `LocalDate`.

Stesso codice, nessuna modifica, lanciato tre volte:

- JDK 21
- JDK 27 con gli header compatti spenti (`-XX:-UseCompactObjectHeaders`)
- JDK 27 così com'è

La seconda serve a capire se la differenza viene davvero dagli header o da altro cambiato tra il 21 e il 27.

## Contenuto

```
rate/PianoRate.java   crea le rate, fa una GC e stampa heap usato e istogramma delle classi
misura.sh             lancia le tre prove e cerca l'heap minimo che basta per ciascuna
prove/                output delle mie esecuzioni
```

Niente Maven o Gradle: il file si lancia direttamente con `java`.

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS, Linux o Windows 10/11 |
| RAM | 4 GB bastano |
| Disco | circa 700 MB per i due JDK |
| JDK 21 | Amazon Corretto 21.0.12.12.1 |
| JDK 27 | Amazon Corretto 27.0.0.35.1 |

Ho usato Corretto per tutti e due, così la differenza non dipende dal fornitore. Con altre distribuzioni di OpenJDK (Temurin, Zulu, quella di Oracle) i risultati dovrebbero essere uguali, perché la disposizione degli oggetti in memoria è codice di HotSpot. Io però non le ho provate tutte: all'inizio avevo fatto le misure con Temurin 21, Microsoft 21 e OpenJDK 27 di Oracle, e i numeri erano gli stessi.

Ho provato tutto su macOS 27, Apple M2 Pro. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

## 1. Scarica i due JDK

Servono tutti e due, uno accanto all'altro. Li mettiamo in una cartella `~/jdk`, senza installarli: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella.

macOS con Apple Silicon (M1, M2, ...):

```sh
mkdir -p ~/jdk
curl -L https://corretto.aws/downloads/latest/amazon-corretto-21-aarch64-macos-jdk.tar.gz | tar -xz -C ~/jdk
curl -L https://corretto.aws/downloads/latest/amazon-corretto-27-aarch64-macos-jdk.tar.gz | tar -xz -C ~/jdk
export JAVA21=~/jdk/amazon-corretto-21.jdk/Contents/Home/bin/java
export JAVA27=~/jdk/amazon-corretto-27.jdk/Contents/Home/bin/java
```

Su un Mac Intel sostituite `aarch64` con `x64` nei due link.

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://corretto.aws/downloads/latest/amazon-corretto-21-x64-linux-jdk.tar.gz | tar -xz -C ~/jdk
curl -L https://corretto.aws/downloads/latest/amazon-corretto-27-x64-linux-jdk.tar.gz | tar -xz -C ~/jdk
export JAVA21="$(ls -d ~/jdk/amazon-corretto-21*)/bin/java"
export JAVA27="$(ls -d ~/jdk/amazon-corretto-27*)/bin/java"
```

Windows: scaricate i due zip e scompattateli dove volete, ad esempio in `C:\jdk`:

- https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip
- https://corretto.aws/downloads/latest/amazon-corretto-27-x64-windows-jdk.zip

Homebrew per ora non va bene: `brew install --cask corretto` installa ancora la 26.

Verifica:

```sh
"$JAVA21" -version
"$JAVA27" -version
```

Deve comparire `Corretto-21.0.12.12.1` per il primo e `Corretto-27.0.0.35.1` per il secondo (o versioni successive, se nel frattempo ne sono uscite).

Sul 27 potete controllare che gli header compatti siano già attivi:

```sh
"$JAVA27" -XX:+PrintFlagsFinal -version | grep UseCompactObjectHeaders
```

```
     bool UseCompactObjectHeaders                  = true                           {product lp64_product} {default}
```

Sul 21 lo stesso comando non stampa niente: il flag non esiste.

## 2. Lancia le prove

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA21` e `JAVA27`:

```sh
./misura.sh
```

Ci mette un paio di minuti: la parte lunga è la ricerca dell'heap minimo, che rilancia il programma con `-Xmx` da 100 MB in su, 5 MB alla volta, finché non va in porto.

Windows (PowerShell), le tre prove a mano. Il nome delle cartelle scompattate contiene la versione: controllate in `C:\jdk`.

```powershell
$JAVA21 = "C:\jdk\<cartella del 21>\bin\java.exe"
$JAVA27 = "C:\jdk\<cartella del 27>\bin\java.exe"
& $JAVA21 rate/PianoRate.java
& $JAVA27 -XX:-UseCompactObjectHeaders rate/PianoRate.java
& $JAVA27 rate/PianoRate.java
```

e per la prova con poca memoria:

```powershell
& $JAVA21 -Xmx160m rate/PianoRate.java
& $JAVA27 -Xmx160m rate/PianoRate.java
```

## Risultato atteso

Per ogni esecuzione il programma stampa la versione di Java, se gli header compatti sono attivi, l'heap usato dopo una GC e le prime righe dell'istogramma delle classi (lo stesso che dà `jcmd <pid> GC.class_histogram`).

```
Java 27+35-FR, header compatti: true
Rate in memoria: 1.200.000
Heap usato dopo la GC: 140 MB

 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:       2400028       76800896  java.math.BigDecimal (java.base@27)
   2:       1200000       38400000  rate.PianoRate$Rata
   3:       1190004       19040064  java.time.LocalDate (java.base@27)
```

Le dimensioni dei singoli oggetti devono venirvi uguali alle mie. L'heap usato può cambiare di qualche MB con un'altra macchina o un altro build.

## Come è andata

### Heap usato

| | Heap dopo la GC | Heap minimo che basta |
|---|---|---|
| JDK 21 | 175 MB | 170 MB |
| JDK 27, header compatti spenti | 175 MB | 170 MB |
| JDK 27 | 140 MB | 140 MB |

35 MB in meno, il 20%, senza toccare una riga. Il JDK 27 con gli header spenti fa esattamente come il 21: il risparmio viene tutto da lì.

L'heap minimo l'ho cercato a passi di 5 MB. Con `-Xmx160m` il 21 finisce in `OutOfMemoryError: Java heap space`, il 27 carica tutte le rate e gli avanzano 20 MB.

Con Corretto ho lanciato ogni configurazione tre volte: l'heap usato varia di un paio di MB. Le soglie le ho cercate più volte, anche con le altre distribuzioni che avevo provato prima, e sono sempre uscite quelle.

### Oggetto per oggetto

Dividendo i byte per il numero di istanze nell'istogramma:

| Classe | JDK 21 | JDK 27 | Istanze |
|---|---|---|---|
| `BigDecimal` | 40 byte | 32 byte | circa 2,4 milioni |
| `Rata` | 40 byte | 32 byte | 1.200.000 |
| `LocalDate` | 24 byte | 16 byte | 1.190.004 |
| `String` | 24 byte | 24 byte | circa 30.700 |

Gli oggetti nella JVM occupano sempre un multiplo di 8 byte. `LocalDate` ha 8 byte di campi (`int` anno, `short` mese e giorno): con 12 byte di header fa 20, arrotondato a 24; con 8 fa 16, già un multiplo di 8. Risparmio: 8 byte, un terzo dell'oggetto.

`String` invece non cambia. Ha 10 byte di campi: 12 + 10 = 22, arrotondato a 24; 8 + 10 = 18, arrotondato ancora a 24. I 4 byte risparmiati se li mangia l'allineamento. Quindi quanto si guadagna dipende da come sono fatte le vostre classi, non è un 4 byte fisso per oggetto.

Le `LocalDate` sono circa 10.000 meno delle rate perché `plusMonths(0)` restituisce la stessa istanza: la prima rata di ogni pratica condivide la data.

### Cosa non ho misurato

La velocità. Il JEP riporta su SPECjbb2015 circa 8% di CPU in meno e 15% di GC in meno, ma per misurarlo serve un benchmark vero (JMH, più giri di riscaldamento), non un programma che gira in un secondo. Non l'ho fatto.

## Se siete su JDK 25

Gli header compatti ci sono già dal JDK 25 (LTS, [JEP 519](https://openjdk.org/jeps/519)), ma vanno accesi a mano con `-XX:+UseCompactObjectHeaders`. Sul 24 erano sperimentali e serviva anche `-XX:+UnlockExperimentalVMOptions`. Non ho provato né il 24 né il 25.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA21 e JAVA27...` | Lo script vuole il percorso completo del comando `java` di ciascun JDK nelle due variabili |
| `Permission denied` su `misura.sh` | `chmod +x misura.sh`, oppure `sh misura.sh` |
| Il JDK 27 dice `header compatti: false` senza flag | `JAVA27` punta a un altro JDK: controllate `"$JAVA27" -version` |
| `Unrecognized VM option 'UseCompactObjectHeaders'` | State passando quel flag a un JDK che non lo conosce, ad esempio il 21 |
| Su Windows, `rate/PianoRate.java` non trovato | Lanciate i comandi dalla cartella di questo README |

## Pulizia

Lo script non lascia niente fuori da `prove/`. Per togliere i due JDK:

```sh
rm -rf ~/jdk
```

Su Windows cancellate `C:\jdk`.

## Link

- JEP 534, Compact Object Headers by Default: https://openjdk.org/jeps/534
- JEP 519, Compact Object Headers (JDK 25): https://openjdk.org/jeps/519
- JDK 27: https://openjdk.org/projects/jdk/27/
- Amazon Corretto: https://aws.amazon.com/corretto/
