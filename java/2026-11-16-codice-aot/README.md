# Le prime 500 richieste in 272 ms invece di 783: il codice compilato nella cache AOT di Java 28

Java 28, che uscirà a marzo 2027, avrà l'Ahead-of-Time Code Compilation ([JEP 544](https://openjdk.org/jeps/544)). Fa parte del Project Leyden, che dal JDK 24 sposta una parte del lavoro della JVM in una corsa di prova, l'addestramento. Quello che la JVM impara durante l'addestramento finisce in un file, la cache AOT, e le corse successive partono da lì.

Questi sono i JEP di Leyden che hanno cambiato cosa c'è nella cache:

| Java | JEP | Cosa aggiunge alla cache | Cosa migliora |
|---|---|---|---|
| 24 | [483](https://openjdk.org/jeps/483) | le classi già caricate e collegate | l'avvio |
| 25 | [515](https://openjdk.org/jeps/515) | i profili dei metodi: quali sono chiamati più spesso e con quali tipi | il riscaldamento, perché il compilatore C2 può partire subito |
| 28 | [544](https://openjdk.org/jeps/544) | il codice nativo già compilato | l'avvio e il riscaldamento |

Fino al 25 la JVM sapeva dalla cache quali metodi compilare, ma doveva compilarli a ogni avvio. Con il 28 trova il codice nativo pronto e lo carica. Il JIT resta al suo posto: se in produzione il carico cambia, ricompila i metodi come ha sempre fatto.

Il codice dell'applicazione resta com'è, e i comandi sono quelli del 25 ([JEP 514](https://openjdk.org/jeps/514)):

```sh
java -XX:AOTCacheOutput=app.aot -jar app.jar   # addestramento
java -XX:AOTCache=app.aot -jar app.jar         # le corse successive
```

## La prova

Un servizio bonifici con Spring Boot 4.1.2: Spring MVC, Spring Data JPA con Hibernate, database H2 in memoria e validazione con Hibernate Validator. Ha due endpoint:

| Endpoint | Cosa fa |
|---|---|
| `POST /bonifici` | controlla gli IBAN (formato italiano e cifre di controllo), sposta l'importo tra due conti e salva il bonifico |
| `GET /conti/{iban}/movimenti` | restituisce gli ultimi 20 bonifici del conto |

All'avvio il database ha 10 conti. Nove hanno un miliardo di euro, il decimo 100 euro: quando un bonifico parte da quel conto e il saldo non basta, il servizio risponde 422.

Il programma in `misura/` avvia il servizio, aspetta la prima risposta e poi manda 10.000 richieste, una alla volta, in 20 lotti da 500. Le richieste sono le stesse a ogni corsa, perché vengono da un generatore casuale con un seme fisso: il 10% sono letture dei movimenti, il 3% bonifici con un IBAN sbagliato, il resto bonifici tra due conti diversi. Per ogni corsa misura:

- dopo quanti millisecondi dall'avvio del processo arriva la prima risposta;
- quanto ci mette ogni lotto da 500 richieste;
- dopo quanti millisecondi dall'avvio arriva l'ultima delle 10.000 risposte.

Ho confrontato quattro modi di avviare il servizio, tutti sulla stessa build del JDK 28:

| Modo | Cache usata | Come è stata addestrata |
|---|---|---|
| `nessuna-cache` | nessuna | |
| `cache-senza-codice` | classi e profili, come nel 25 | con le stesse 10.000 richieste |
| `cache-con-codice` | classi, profili e codice compilato | con le stesse 10.000 richieste |
| `cache-solo-avvio` | classi, profili e codice compilato | come suggerisce la documentazione di Spring Boot: il servizio parte e si chiude subito, senza richieste |

Per la cache senza codice non ho usato il JDK 25 ma l'opzione diagnostica `-XX:-AOTCodeCaching` del 28, che il JEP indica proprio per confrontare la cache con e senza codice. La uso sia nell'addestramento sia nelle corse.

Negli addestramenti con le richieste, alla fine `Misura.java` ferma il servizio con un SIGTERM e la JVM, uscendo, scrive la cache. Nell'addestramento senza richieste il servizio esce da solo dopo l'avvio, per via di `-Dspring.context.exit=onRefresh`.

Ogni modo è stato lanciato 7 volte, alternando i modi: prima la corsa 1 dei quattro, poi la corsa 2 e così via. Così un rallentamento del Mac nel mezzo della prova pesa su tutti allo stesso modo. Nei risultati c'è la mediana delle 7 corse.

## Contenuto

```
servizio/                     il servizio Spring Boot (Maven, con il wrapper mvnw)
servizio/src/main/java/...    entità, repository, servizio, controller e validazione dell'IBAN
servizio/src/main/resources/  configurazione e i 10 conti iniziali
misura/Misura.java            addestra le cache, confronta i quattro modi, prova gli altri garbage collector
misura/Modo.java              i quattro modi e le loro opzioni
misura/Cache.java             le tre cache e le opzioni per crearle e usarle
misura/Servizio.java          avvia e ferma la JVM del servizio
misura/Carico.java            le 10.000 richieste
prova.sh                      build, addestramento, confronto e prova dei garbage collector
prove/                        output delle mie esecuzioni
```

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (lo script è per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 2 GB liberi |
| Disco | circa 450 MB per il JDK, 100 MB per Maven e le librerie, 600 MB per la build e le tre cache |
| JDK | OpenJDK 28 early access, build 28-ea+24 del 12 novembre 2026 |
| Altro | `curl` (su macOS c'è già); Maven non serve, lo scarica `mvnw` |
| Porta | la 8080 libera |

Ho provato tutto su macOS 27.2.1, con un Apple M2 Pro da 10 core (6 performance e 4 efficiency) e 16 GB di RAM, mentre sul Mac giravano i soliti programmi. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Le build early access non sono fatte per la produzione e cambiano ogni settimana.

## 1. Scarica il JDK 28

Lo mettiamo in una cartella `~/jdk`, senza installarlo: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella.

macOS con Apple Silicon (M1, M2, ...):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/24/GPL/openjdk-28-ea+24_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28.jdk/Contents/Home/bin/java
```

Per i Mac Intel non c'è una build early access.

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/24/GPL/openjdk-28-ea+24_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA28=~/jdk/jdk-28/bin/java
```

Windows: scaricate lo zip e scompattatelo dove volete, ad esempio in `C:\jdk`:

- https://download.java.net/java/early_access/jdk28/24/GPL/openjdk-28-ea+24_windows-x64_bin.zip

Quando esce una build nuova, quella vecchia di solito sparisce dal sito. Se il link risponde 404 prendete l'ultima da https://jdk.java.net/28/ e cambiate il numero della build nei comandi.

Verifica:

```sh
"$JAVA28" -version
```

Deve comparire `openjdk version "28-ea"` e `build 28-ea+24` (o un numero di build più alto).

## 2. Lancia la prova

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA28`:

```sh
./prova.sh
```

Sul mio Mac ci mette meno di 3 minuti, più il tempo di scaricare Maven e le librerie la prima volta. Lo script:

1. compila il servizio con `mvnw` e il JDK 28;
2. estrae il jar di Spring Boot in `servizio/target/estratto`, perché la cache AOT funziona con i jar normali sul classpath e non con quelli dentro il jar di Spring Boot;
3. addestra le tre cache (`prove/addestramento.txt`);
4. lancia il confronto, 7 corse per modo (`prove/confronto.txt`);
5. prova la cache con altri garbage collector (`prove/altri-gc.txt`).

Le cache vanno in `servizio/target/aot/` e i log di ogni avvio del servizio in `servizio/target/log/`.

Windows (PowerShell), dalla cartella di questo README:

```powershell
$JAVA28 = "C:\jdk\jdk-28\bin\java.exe"
$env:JAVA_HOME = "C:\jdk\jdk-28"
cd servizio; .\mvnw.cmd -q -B package; cd ..
& $JAVA28 -Djarmode=tools -jar servizio/target/bonifici.jar extract --destination servizio/target/estratto
& $JAVA28 misura/Misura.java addestra
& $JAVA28 misura/Misura.java confronta
& $JAVA28 misura/Misura.java altri-gc
```

## Come è andata

L'addestramento, da `prove/addestramento.txt`:

```
Java 28-ea+24-1988, addestramento
senza-codice.aot: 148 MB, pronta dopo 12.994 ms, risposte 1.027 con codice 200, 8.539 con codice 201, 307 con codice 400, 127 con codice 422
con-codice.aot: 198 MB, pronta dopo 19.928 ms, risposte 1.027 con codice 200, 8.539 con codice 201, 307 con codice 400, 127 con codice 422
solo-avvio.aot: 140 MB, pronta dopo 9.267 ms, nessuna richiesta
```

Con il codice compilato la cache pesa 50 MB in più e l'addestramento dura 7 secondi in più, perché prima di scrivere il file la JVM compila i metodi che ha visto usare di più.

Le mediane del confronto, da `prove/confronto.txt`:

| Modo | Prima risposta | Primo lotto da 500 | Secondo lotto | Ultima delle 10.000 risposte |
|---|---|---|---|---|
| `nessuna-cache` | 1.980 ms | 783 ms | 366 ms | 5.660 ms |
| `cache-senza-codice` | 724 ms | 702 ms | 239 ms | 3.650 ms |
| `cache-con-codice` | 649 ms | **272 ms** | 154 ms | 3.023 ms |
| `cache-solo-avvio` | 741 ms | 801 ms | 385 ms | 4.526 ms |

In tutte le 28 corse il servizio ha dato le stesse risposte: 1.027 letture dei movimenti, 8.539 bonifici eseguiti, 307 rifiutati per l'IBAN sbagliato e 127 per il saldo insufficiente. Con la cache sono cambiati solo i tempi.

### All'avvio

La prima risposta arriva dopo 1.980 ms senza cache e dopo 724 ms con la cache senza codice: il 63% in meno, e lo fanno le classi già caricate. Il codice compilato toglie altri 75 ms, fino a 649.

### Nelle prime richieste

Il primo lotto da 500 richieste ci mette 272 ms con il codice nella cache, 702 con la cache senza codice e 783 senza cache: 2,9 volte più veloce che senza cache. Nelle 7 corse il primo lotto con il codice è sempre rimasto tra 267 e 279 ms, quello senza codice tra 692 e 705.

Senza codice nella cache, nelle prime richieste i metodi girano nell'interprete finché il JIT non li compila, come quando la cache non c'è. Con il codice nella cache, i metodi che la JVM ha visto usare di più durante l'addestramento sono compilati già alla prima richiesta.

Dal tredicesimo lotto in poi le mediane dei quattro modi stanno tutte tra 88 e 116 ms: a regime il servizio va alla stessa velocità, e con il codice nella cache ci arriva prima.

Le mediane dei 20 lotti, in millisecondi:

```
nessuna-cache       783 366 267 211 185 177 161 156 150 136 125 122 110 114 112 114 104 107  96  98
cache-senza-codice  702 239 165 152 129 114 117 113 112 103 104  99  99 100 101  97  97  92  92  92
cache-con-codice    272 154 147 136 127 117 110 105  99 106  98  96  95 104 105  98 102  98  91  98
cache-solo-avvio    801 385 269 220 194 176 164 160 148 132 128 138 113 115 116 111 100 101  88  94
```

### Con l'addestramento senza richieste

La [documentazione di Spring Boot](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html) suggerisce di addestrare la cache così:

```sh
java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar my-app.jar
```

Il servizio parte, Spring lo chiude appena il contesto è pronto e nessuna richiesta passa. La cache che ne esce (`cache-solo-avvio`) aiuta l'avvio come le altre, con la prima risposta dopo 741 ms. Per le prime richieste invece non cambia niente: il primo lotto ci mette 801 ms, più o meno come senza cache (783), perché durante l'addestramento il codice che gestisce le richieste non è mai girato e nella cache non c'è.

Per avere il codice delle richieste nella cache, durante l'addestramento le richieste devono passare: un test di carico, o il traffico di un ambiente di prova, prima di fermare il servizio.

## Con un altro garbage collector

Il codice compilato contiene istruzioni che dipendono dal garbage collector, per questo il JEP chiede di usare lo stesso collector nell'addestramento e nelle corse successive. Ho usato la cache addestrata con G1, il collector predefinito, con il Parallel GC e con ZGC. Con `-XX:AOTMode=required` la JVM si ferma se non può usare la cache e scrive il motivo (`prove/altri-gc.txt`):

```
Java 28-ea+24-1988, con-codice.aot addestrata con G1, usata con -XX:AOTMode=required
-XX:+UseG1GC
[0.029s][info][aot,codecache,init] Loaded 13523 AOT code entries from AOT Code Cache
-XX:+UseParallelGC
[0.030s][error][aot,codecache,init] AOT Code Cache disabled: it was created with UseCondCardMark = true vs current false
Error occurred during initialization of VM
Unable to use AOT Code Cache.
-XX:+UseZGC
[0.004s][warning][aot] Unable to use AOT cache.
[                    ] The saved state of UseCompressedOops (1) is different from runtime (0), CDS will be disabled.
[0.004s][error  ][aot] Unable to map shared spaces
Error occurred during initialization of VM
Unable to use AOT cache.: servizio/target/aot/con-codice.aot
```

Con G1 la JVM carica 13.523 voci di codice compilato, tra metodi e pezzi di codice di servizio della JVM. Con il Parallel GC scarta il codice compilato; il resto della cache, secondo il JEP, resta valido. Con ZGC scarta tutta la cache, perché ZGC non usa i puntatori compressi (`UseCompressedOops`) e la cache è stata scritta con G1, che li usa.

Senza `AOTMode=required` il servizio parte lo stesso in tutti e tre i casi. Con ZGC la JVM stampa il warning `Unable to use AOT cache`. Con il Parallel GC la build 24 non stampa niente, e il messaggio sul codice scartato si vede solo con il log attivo (`-Xlog:aot+codecache+init=info`). Se in produzione il collector non è quello dell'addestramento, conviene provare almeno una volta l'avvio con `AOTMode=required`.

## Spring Boot sulla build 24

Sulla build 24 il servizio non partiva:

```
Caused by: org.springframework.aop.framework.AopConfigException: Unable to instantiate proxy using Objenesis, and regular proxy instantiation via default constructor fails as well
Caused by: java.lang.NoSuchMethodException: bonifici.Sportello$$SpringCGLIB$$0.<init>()
```

`Sportello` ha metodi `@Transactional` e un costruttore con argomenti. Per una classe così Spring crea il proxy con Objenesis, che usa un metodo interno del JDK, `ReflectionFactory.newConstructorForSerialization`. Dalla build 24 quel metodo non funziona più per le classi che non sono `Serializable` (JDK-8393262, nelle [note di rilascio](https://jdk.java.net/28/release-notes) della build 24). Le note indicano l'opzione che per ora rimette le cose come prima:

```
-Djdk.reflect.allowNonSerializableConstruction=true
```

`misura/Servizio.java` la aggiunge a ogni avvio del servizio. L'ho provato con Spring Boot 4.1.2, cioè Spring Framework 7.0.10.

## Cosa non ho provato

- Linux, Windows e i processori x64: il JEP supporta AArch64 e x64, io ho usato solo AArch64 su macOS.
- Un container con pochi core. I numeri del JEP vengono da una macchina Linux con 2 core, dove il JIT si contende la CPU con l'applicazione. Sul mio Mac con 10 core il JIT ha CPU libera, e in un container piccolo i risultati potrebbero essere diversi.
- Il JDK 25 vero per la cache senza codice: ho usato l'opzione `-XX:-AOTCodeCaching` del 28.
- Un addestramento con richieste diverse da quelle misurate: qui l'addestramento manda le stesse 10.000 richieste delle corse.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` del JDK 28 nella variabile `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `Sulla porta 8080 risponde già qualcosa` | Fermate il programma che usa la porta 8080, ad esempio un altro Spring Boot |
| `il servizio si è fermato da solo` o `non risponde dopo 60 secondi` | Guardate il log indicato nel messaggio, in `servizio/target/log/` |
| `Unable to instantiate proxy using Objenesis` | State avviando il servizio a mano senza `-Djdk.reflect.allowNonSerializableConstruction=true` (vedi sopra) |
| `An error has occurred while writing the AOT configuration file` | La cartella della cache non esiste e la JVM non la crea. `Misura.java` la crea, a mano serve `mkdir -p` |
| Nel log dell'addestramento tanti `Preload Warning: Verification failed for ...` | Sono classi di Spring e Hibernate che servono solo con librerie che il servizio non ha (Reactor, Log4j, FreeMarker): la JVM le lascia fuori dalla cache |
| `Unable to use AOT cache` | La cache è stata creata con un'altra build del JDK, un altro jar o un altro garbage collector: rilanciate `prova.sh`, che la ricrea |
| `Permission denied` su `prova.sh` o `mvnw` | `chmod +x prova.sh servizio/mvnw`, oppure `sh prova.sh` |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 24 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |

## Pulizia

```sh
rm -rf ~/jdk servizio/target
```

`mvnw` lascia Maven in `~/.m2/wrapper/dists/apache-maven-3.10.0` e le librerie di Spring in `~/.m2/repository`: se non usate Maven per altro potete cancellare anche quelle cartelle. Su Windows cancellate `C:\jdk`.

## Link

- JEP 544, Ahead-of-Time Code Compilation: https://openjdk.org/jeps/544
- JEP 483, Ahead-of-Time Class Loading & Linking: https://openjdk.org/jeps/483
- JEP 514, Ahead-of-Time Command-Line Ergonomics: https://openjdk.org/jeps/514
- JEP 515, Ahead-of-Time Method Profiling: https://openjdk.org/jeps/515
- Project Leyden: https://openjdk.org/projects/leyden/
- JDK 28 early access e note di rilascio: https://jdk.java.net/28/
- Spring Boot e la cache AOT: https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html
