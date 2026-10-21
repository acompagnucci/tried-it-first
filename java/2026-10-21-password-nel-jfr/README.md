# La password nel file JFR, su JDK 21 e su JDK 27

JDK Flight Recorder (JFR) è la scatola nera della JVM: mentre l'applicazione gira registra GC, thread, lock, I/O, e alla fine scrive tutto in un file `.jfr`. All'avvio ci scrive anche come è stata lanciata la JVM: la riga di comando, le system property e le variabili d'ambiente. Quindi anche `-Ddb.password=...`.

Con JDK 27, uscito il 15 settembre 2026, JFR sostituisce i valori sensibili con `[REDACTED]` prima di scriverli ([JEP 536](https://openjdk.org/jeps/536)). È attivo di default. Per system property e variabili d'ambiente decide dal nome della chiave: `*password*`, `*secret*`, `*token*`, `*pwd*` e qualche altro.

Volevo vedere cosa resta nel file con i modi in cui di solito la password del database arriva a un servizio: con `-D`, in una variabile d'ambiente, dentro l'URL. Ho messo sei valori finti, da `SEGRETO1` a `SEGRETO6`, in posti diversi:

| Valore | Dove |
|---|---|
| `SEGRETO1` | dentro l'URL JDBC: `-Ddb.url=jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1` |
| `SEGRETO2` | `-Ddb.password=SEGRETO2` |
| `SEGRETO3` | `-Ddb.pass=SEGRETO3` |
| `SEGRETO4` | dentro un URL in variabile d'ambiente: `DATABASE_URL=postgres://app:SEGRETO4@db:5432/pratiche` |
| `SEGRETO5` | `DB_PASSWORD=SEGRETO5` |
| `SEGRETO6` | `DB_PASS=SEGRETO6` |

Poi ho lanciato lo stesso programma con JFR acceso su JDK 21 e su JDK 27, e ho cercato i sei valori dentro il file.

## Contenuto

```
servizio/Servizio.java   legge URL e password del database e li stampa, per vedere cosa riceve davvero
prova.sh                 lancia le prove con JFR e cerca i valori dentro le registrazioni
prove/                   output delle mie esecuzioni
```

Niente Maven o Gradle: il file si lancia direttamente con `java`.

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS o Linux (lo script è per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 2 GB bastano |
| Disco | circa 700 MB per i due JDK |
| JDK 21 | Amazon Corretto 21.0.12.12.1 |
| JDK 27 | Amazon Corretto 27.0.0.35.1 |

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

Verifica:

```sh
"$JAVA21" -version
"$JAVA27" -version
```

Deve comparire `Corretto-21.0.12.12.1` per il primo e `Corretto-27.0.0.35.1` per il secondo (o versioni successive, se nel frattempo ne sono uscite).

Lo script usa anche `jfr` e `jcmd`, che stanno nella stessa cartella `bin` di `java`: non c'è altro da installare.

## 2. Lancia le prove

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA21` e `JAVA27`:

```sh
./prova.sh
```

Ci mette una quindicina di secondi e fa cinque cose:

1. lancia il programma con JFR su JDK 21
2. lo stesso su JDK 27
3. di nuovo su JDK 27, aggiungendo dei pattern per i nomi che il 27 non riconosce da solo
4. pulisce la registrazione del 21 con `jfr scrub`
5. lancia il programma su JDK 27 e, mentre gira, legge password e riga di comando con `jcmd`

Per ogni registrazione stampa cosa c'è negli eventi `jdk.JVMInformation`, `jdk.InitialSystemProperty` e `jdk.InitialEnvironmentVariable`, e quali dei sei valori si trovano cercandoli direttamente nel file con `grep`.

Il programma parte con `env -i`, cioè con un ambiente vuoto più le variabili della prova, `PATH` e `PWD`. Senza, nel file del 21 finirebbero in chiaro anche tutte le vostre variabili d'ambiente, e non è il caso di lasciarle in giro dentro `prove/`. Per lo stesso motivo nei `.txt` il percorso della cartella è sostituito con `<cartella>`, e i file `.jfr` non sono nel repository.

Windows (PowerShell), le prime due prove a mano:

```powershell
$JAVA21 = "C:\jdk\<cartella del 21>\bin"
$JAVA27 = "C:\jdk\<cartella del 27>\bin"
$env:DATABASE_URL = "postgres://app:SEGRETO4@db:5432/pratiche"
$env:DB_PASSWORD = "SEGRETO5"
$env:DB_PASS = "SEGRETO6"
mkdir prove
foreach ($v in "21", "27") {
    $bin = if ($v -eq "21") { $JAVA21 } else { $JAVA27 }
    & "$bin\java.exe" "-XX:StartFlightRecording:filename=prove/jdk$v.jfr" `
        "-Ddb.url=jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1" `
        -Ddb.password=SEGRETO2 -Ddb.pass=SEGRETO3 servizio/Servizio.java 0
    & "$bin\jfr.exe" print --events jdk.InitialSystemProperty,jdk.InitialEnvironmentVariable "prove/jdk$v.jfr" |
        Select-String -Pattern 'SEGRETO|REDACTED'
}
```

Qui l'ambiente non è pulito: le vostre variabili finiscono nel file del 21.

## Risultato atteso

Il programma stampa la versione di Java e i valori che riceve. Su JDK 27 sono gli stessi del 21: la redazione cambia solo cosa finisce nel file, non cosa legge l'applicazione.

```
Java 27+35-FR
-Ddb.url       jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1
-Ddb.password  SEGRETO2
-Ddb.pass      SEGRETO3
DATABASE_URL   postgres://app:SEGRETO4@db:5432/pratiche
DB_PASSWORD    SEGRETO5
DB_PASS        SEGRETO6
```

Alla fine lo script riassume:

```
== prove/jdk21.txt
Segreti che si leggono nel file: SEGRETO1 SEGRETO2 SEGRETO3 SEGRETO4 SEGRETO5 SEGRETO6
== prove/jdk27.txt
Segreti che si leggono nel file: SEGRETO1 SEGRETO3 SEGRETO4 SEGRETO6
== prove/jdk27-pattern-aggiunti.txt
Segreti che si leggono nel file: nessuno
== prove/jdk21-scrub.txt
Segreti che si leggono in prove/jdk21-scrub.jfr: nessuno
```

## Come è andata

### JDK 21: tutto in chiaro

Tutti e sei i valori sono nel file. Le tre system property ci sono due volte, nella riga di comando (`jdk.JVMInformation`) e nell'elenco delle property (`jdk.InitialSystemProperty`):

```
  jvmArguments = "-XX:StartFlightRecording:filename=prove/jdk21.jfr -Ddb.url=jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1 -Ddb.password=SEGRETO2 -Ddb.pass=SEGRETO3 --add-modules=ALL-DEFAULT"
  db.password = "SEGRETO2"
  DB_PASSWORD = "SEGRETO5"
```

### JDK 27: ne restano quattro su sei

```
  jvmArguments = "-XX:StartFlightRecording:filename=prove/jdk27.jfr [REDACTED] [REDACTED] -Ddb.pass=SEGRETO3 --add-modules=ALL-DEFAULT"
  db.pass = "SEGRETO3"
  db.password = "[REDACTED]"
  db.url = "jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1"
  DATABASE_URL = "postgres://app:SEGRETO4@db:5432/pratiche"
  PWD = "[REDACTED]"
  DB_PASSWORD = "[REDACTED]"
  DB_PASS = "SEGRETO6"
```

| Valore | JDK 21 | JDK 27 | Perché |
|---|---|---|---|
| `SEGRETO1`, URL JDBC | in chiaro | in chiaro | vedi sotto |
| `SEGRETO2`, `db.password` | in chiaro | `[REDACTED]` | la chiave contiene `password` |
| `SEGRETO3`, `db.pass` | in chiaro | in chiaro | nessun pattern per `pass` da solo |
| `SEGRETO4`, `DATABASE_URL` | in chiaro | in chiaro | la chiave è `DATABASE_URL`, non dice niente |
| `SEGRETO5`, `DB_PASSWORD` | in chiaro | `[REDACTED]` | la chiave contiene `password` |
| `SEGRETO6`, `DB_PASS` | in chiaro | in chiaro | come `db.pass` |

L'URL JDBC è il caso più curioso. Nella riga di comando l'argomento `-Ddb.url=...` viene oscurato tutto, perché da qualche parte contiene la parola `password`. Nell'elenco delle system property invece JFR guarda solo il nome, `db.url`, e il valore resta in chiaro con la password dentro.

È scritto anche nel JEP: per property e variabili d'ambiente conta il nome della chiave, per la riga di comando il testo dell'argomento. Quindi `db.pass`, `DB_PASS` e le password dentro un URL passano, perché il nome non contiene nessuno dei pattern.

### PWD è un segreto

Su JDK 27 diventa `[REDACTED]` anche `PWD`, che è solo la cartella da cui è partito il programma: `*pwd*` è tra i pattern di default, pensato per le abbreviazioni di password. In una prova fatta a parte, con il mio ambiente completo, era sparito anche `SSH_AUTH_SOCK`, per colpa di `*auth*`. Non è grave, ma se cercate la cartella di lavoro in una registrazione del 27 non la trovate più.

### Aggiungere i propri pattern

I pattern si estendono con `-XX:FlightRecorderOptions`. Il `+` davanti vuol dire "in aggiunta a quelli di default":

```sh
"$JAVA27" '-XX:FlightRecorderOptions:redact-key=+*pass*;*url*,redact-argument=+*pass*' ...
```

- `redact-key` vale per system property e variabili d'ambiente, `redact-argument` per la riga di comando
- i pattern sono separati da `;`, le due opzioni da `,`; gli apici servono per la shell

Con questi, nel file non resta nessuno dei sei valori (`prove/jdk27-pattern-aggiunti.txt`). Il prezzo è che `*url*` nasconde tutto l'URL, host e nome del database compresi, e `DATABASE_URL` anche se non avesse la password dentro. Se volete vedere cosa viene oscurato, `-Xlog:jfr+redact=debug` stampa una riga per ogni valore.

Viene oscurato anche l'argomento `-XX:FlightRecorderOptions:...` stesso, perché contiene `pass`.

### jcmd non oscura niente

Mentre il programma gira su JDK 27 con JFR acceso, `jcmd` mostra tutto (`prove/jdk27-jcmd.txt`):

```
jcmd VM.system_properties:
db.pass=SEGRETO3
db.password=SEGRETO2
db.url=jdbc\:postgresql\://db\:5432/pratiche?user\=app&password\=SEGRETO1

jcmd VM.command_line:
jvm_args: -XX:StartFlightRecording:filename=prove/jdk27-jcmd.jfr -Ddb.url=jdbc:postgresql://db:5432/pratiche?user=app&password=SEGRETO1 -Ddb.password=SEGRETO2 -Ddb.pass=SEGRETO3 --add-modules=ALL-DEFAULT
```

Il JEP riguarda solo JFR. Chi può lanciare `jcmd` sul processo vede le password come prima, e la riga di comando la vede anche `ps` (l'ho controllato a mano, non è nello script).

## Se restate su JDK 21

Il 21 ha `jfr scrub`, che riscrive una registrazione togliendo gli eventi che indicate:

```sh
"$(dirname "$JAVA21")/jfr" scrub \
    --exclude-events jdk.JVMInformation,jdk.InitialSystemProperty,jdk.InitialEnvironmentVariable \
    prove/jdk21.jfr prove/jdk21-scrub.jfr
```

Nel file pulito non c'è nessuno dei sei valori. Però perdete gli eventi interi, anche `-Xmx` e le property che servirebbero per capire un problema, e va fatto ogni volta prima di passare il file a qualcuno. Sul JDK 17 `jfr scrub` non c'è; sul 23 sì.

Il JEP 536 è arrivato con il 27. Il 25 LTS non l'ho provato.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA21 e JAVA27...` | Lo script vuole il percorso completo del comando `java` di ciascun JDK nelle due variabili |
| `Permission denied` su `prova.sh` | `chmod +x prova.sh`, oppure `sh prova.sh` |
| Su JDK 27 non compare nessun `[REDACTED]` | `JAVA27` punta a un altro JDK: controllate `"$JAVA27" -version` |
| `jfr: No such file or directory` | `JAVA21` o `JAVA27` puntano a un `java` fuori dalla cartella `bin` del JDK (ad esempio `/usr/bin/java`): usate il percorso completo |
| `jcmd non trova il programma lanciato su JDK 27` | Lo script aspetta 8 secondi che il programma compaia in `jcmd -l`. Su una macchina lenta alzate il numero di tentativi nel ciclo `for` di `prova.sh` |
| Su Windows, `servizio/Servizio.java` non trovato | Lanciate i comandi dalla cartella di questo README |

## Pulizia

Lo script non lascia niente fuori da `prove/`. Le registrazioni `.jfr` sono lì: cancellatele se non vi servono, quelle del 21 hanno i valori in chiaro.

```sh
rm prove/*.jfr
rm -rf ~/jdk
```

Su Windows cancellate `C:\jdk`.

## Link

- JEP 536, JFR In-Process Data Redaction: https://openjdk.org/jeps/536
- JDK 27: https://openjdk.org/projects/jdk/27/
- Il comando `jfr`: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html
- Amazon Corretto: https://aws.amazon.com/corretto/
