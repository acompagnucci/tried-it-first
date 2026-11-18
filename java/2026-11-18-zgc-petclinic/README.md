# PetClinic con 1 GB di heap invece di 7,6: l'heap adattivo di ZGC in Java 28

Java 28, che uscirà a marzo 2027, avrà due JEP che cambiano il modo in cui ZGC decide quanta memoria prendere per l'heap:

| JEP | Cosa cambia |
|---|---|
| [545](https://openjdk.org/jeps/545), Faster Startup and Warmup with ZGC | l'heap parte piccolissimo e cresce in anticipo, a passi sempre più grandi, con i thread del GC e non con quelli dell'applicazione; la memoria nuova viene toccata mentre l'heap cresce e non tutta all'avvio; su Linux 6.1 e successivi usa le pagine da 2 MB |
| [546](https://openjdk.org/jeps/546), Adaptive Heap Sizing for ZGC | con `-XX:+ZAdaptiveHeapSizing` ZGC sceglie da solo quanto heap tenere, cercando un equilibrio tra la memoria usata e la CPU che spende a raccogliere; il massimo diventa quasi tutta la RAM, e se il sistema resta senza memoria l'heap si restringe |

Fino a Java 27 con ZGC si sceglieva a mano tra due cose:

- `-Xms` piccolo: la JVM parte prima, ma l'heap cresce mentre arrivano le richieste e nei primi secondi le risposte rallentano;
- `-Xms` uguale a `-Xmx` più `-XX:+AlwaysPreTouch`: tutta la memoria viene presa e toccata all'avvio, la JVM parte dopo, ma da subito non deve chiedere niente al sistema operativo.

Con Java 28 il JEP propone di non scegliere più e di togliere `-Xms` e `-Xmx`:

```sh
java -XX:+UseZGC -XX:+ZAdaptiveHeapSizing -jar app.jar
```

Il JEP 545 lo prova con [Spring PetClinic](https://github.com/spring-projects/spring-petclinic), e io ho rifatto lo stesso caso sul mio Mac.

## La prova

PetClinic è l'applicazione di esempio di Spring Boot: Spring MVC, Thymeleaf, JPA con Hibernate e il database H2 in memoria. Ho usato il commit `500158f` del 29 settembre 2026, con Spring Boot 4.1.0.

Il caso di studio del JEP e il mio:

| | JEP 545 | Io |
|---|---|---|
| Macchina | 32 GB di RAM | Apple M2 Pro con 16 GB, con i soliti programmi aperti |
| Heap grande | 20 GB | 8 GB, la metà della RAM come nel JEP |
| Carico | una richiesta, poi da 1.000 a 30.000 richieste al secondo in 10 secondi, poi 30.000 al secondo per 2 minuti | una richiesta, poi da 20 a 600 richieste al secondo in 10 secondi, poi 600 al secondo per 2 minuti |
| Generatore di carico | non indicato | `misura/Carico.java`, sullo stesso Mac |
| Soglia | P99 sotto 20 ms | P99 sotto 20 ms |

Il P99 è il tempo entro cui arriva il 99% delle risposte di quel secondo. Il carico non può essere quello del JEP. Prima della prova ho cercato quanto regge PetClinic sul mio Mac: a 1.100 richieste al secondo risponde, a 1.400 le richieste si accumulano e il Mac comincia a rifiutare le connessioni. Ho tenuto la stessa forma, con lo stesso rapporto 1 a 30 tra l'inizio e la fine della rampa, e 600 richieste al secondo a regime, circa la metà di quello che il servizio regge.

Gli scenari sono i tre del JEP più uno, Java 28 con le stesse opzioni del primo, per vedere cosa cambia senza l'opzione nuova:

| Scenario | Java | Opzioni |
|---|---|---|
| `java25-heap-piccolo` | 25.0.2 | `-XX:+UseZGC -Xms2M -Xmx8G` |
| `java25-heap-grande` | 25.0.2 | `-XX:+UseZGC -Xms8G -Xmx8G -XX:+AlwaysPreTouch` |
| `java28-heap-piccolo` | 28-ea+24 | `-XX:+UseZGC -Xms2M -Xmx8G` |
| `java28-adattivo` | 28-ea+24 | `-XX:+UseZGC -XX:+ZAdaptiveHeapSizing` |

Per ogni corsa `misura/Misura.java`:

1. avvia PetClinic e misura dopo quanti millisecondi risponde la home;
2. manda il carico: 75.099 richieste in 130 secondi, a turno su cinque pagine (`/owners/1`, `/owners?lastName=Davis`, `/vets.html`, `/owners/5`, `/owners?lastName=`);
3. ogni secondo calcola P50 e P99 delle risposte;
4. legge dal log del GC quanto è grande l'heap secondo per secondo.

Il generatore manda le richieste all'ora prevista anche se il servizio rallenta, e misura ogni risposta da quell'ora. Così un rallentamento pesa su tutte le richieste che arrivano nel frattempo, come succederebbe con utenti veri, e non solo sulla prima.

L'heap è la colonna `Capacity` del log `gc+heap` di ZGC: la memoria che la JVM ha preso per l'heap, usata o libera. Non ho usato la memoria del processo letta con `ps`, perché macOS comprime le pagine poco usate e il numero cambia anche se l'heap resta lo stesso.

Ogni scenario è stato lanciato 5 volte, alternando gli scenari: prima la corsa 1 dei quattro, poi la corsa 2 e così via. Nei risultati c'è la mediana delle 5 corse.

## Contenuto

```
misura/Misura.java      avvia PetClinic negli scenari, manda il carico, scrive i risultati
misura/Scenario.java    i quattro scenari e le loro opzioni
misura/PetClinic.java   avvia e ferma la JVM, legge l'heap dal log del GC
misura/Carico.java      il carico, secondo per secondo
misura/CicliDelGc.java  conta i cicli di ZGC nei log e ne somma la durata
prova.sh                scarica e compila PetClinic, lancia tutto
prove/                  output delle mie esecuzioni
```

In `prove/`:

| File | Cosa contiene |
|---|---|
| `heap-predefinito.txt` | heap iniziale e massimo che la JVM sceglie senza `-Xms` e `-Xmx` |
| `confronto.txt` | il riassunto di ogni corsa e le mediane |
| `secondi/<scenario>-<corsa>.tsv` | per ogni secondo: richieste, P50, P99 in millisecondi e heap in MB |
| `gc/<scenario>-<corsa>.log` | il log del GC di ogni corsa (`-Xlog:gc`) |
| `cicli-gc.txt` | quanti cicli di ZGC in ogni corsa e quanto sono durati in tutto |
| `swap-mac.txt` | lo swap del Mac ogni 5 secondi, da metà della corsa 3 alla fine (vedi più sotto) |
| `corsa-di-controllo.txt` | una corsa in più per scenario, fatta dopo con `misura/Misura.java 1` per controllare il codice nella versione pubblicata |

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (lo script è per `sh`) |
| RAM | 16 GB: lo scenario con heap grande prende 8 GB all'avvio |
| Disco | circa 820 MB per i due JDK, 80 MB per PetClinic, più Maven e le librerie che scarica `mvnw` |
| JDK | un JDK 25 (io ho usato OpenJDK 25.0.2) e OpenJDK 28 early access, build 28-ea+24 del 12 novembre 2026 |
| Altro | `git` e `curl`; Maven non serve, lo scarica il `mvnw` di PetClinic |
| Porta | la 8080 libera |
| Tempo | circa 45 minuti per 5 corse dei quattro scenari |

Ho provato tutto su macOS 27.2.1, con un Apple M2 Pro da 10 core e 16 GB di RAM. I comandi per Linux non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Le build early access non sono fatte per la produzione e cambiano ogni settimana.

## 1. Scarica i JDK

Li mettiamo in una cartella `~/jdk`, senza installarli: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella. Se avete già un JDK 25, saltate il primo download e impostate `JAVA25` con il suo comando `java`.

macOS con Apple Silicon:

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/24/GPL/openjdk-28-ea+24_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA25=~/jdk/jdk-25.0.2.jdk/Contents/Home/bin/java
export JAVA28=~/jdk/jdk-28.jdk/Contents/Home/bin/java
```

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/24/GPL/openjdk-28-ea+24_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA25=~/jdk/jdk-25.0.2/bin/java
export JAVA28=~/jdk/jdk-28/bin/java
```

Quando esce una build early access nuova, quella vecchia di solito sparisce dal sito. Se il link del JDK 28 risponde 404 prendete l'ultima da https://jdk.java.net/28/ e cambiate il numero della build nel comando.

Verifica:

```sh
"$JAVA25" -version
"$JAVA28" -version
```

Devono comparire `openjdk version "25` e `openjdk version "28-ea"`.

## 2. Lancia la prova

Dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA25` e `JAVA28`:

```sh
./prova.sh
```

Lo script:

1. scarica PetClinic in `target/petclinic` e si mette sul commit della prova;
2. lo compila con il JDK 25 e copia il jar in `target/petclinic.jar`;
3. scrive heap iniziale e massimo che la JVM sceglie da sola (`prove/heap-predefinito.txt`);
4. lancia le 5 corse dei quattro scenari (`prove/confronto.txt`, `prove/secondi/`, `prove/gc/`);
5. conta i cicli del GC (`prove/cicli-gc.txt`).

Il programma di misura gira sul JDK 25, con il suo garbage collector predefinito: è lo stesso in tutte le corse, cambia solo la JVM di PetClinic. I log completi di ogni avvio, compreso quello `gc+heap` da cui si legge l'heap, restano in `target/log/`.

Per una prova veloce con una corsa sola per scenario, circa 10 minuti:

```sh
./prova.sh 1
```

Attenzione: sovrascrive i file in `prove/`.

Durante la prova chiudete quello che non serve: il Mac lavora da generatore di carico e da server insieme, e lo scenario con heap grande prende 8 GB.

## Come è andata

### Heap iniziale e massimo

Senza `-Xms` e `-Xmx`, sul mio Mac da 16 GB (`prove/heap-predefinito.txt`):

```
Java 25 -XX:+UseZGC: heap iniziale 256 MB, massimo 4096 MB
Java 28 -XX:+UseZGC: heap iniziale 8 MB, massimo 4096 MB
Java 28 -XX:+UseZGC -XX:+ZAdaptiveHeapSizing: heap iniziale 2 MB, massimo 16384 MB
```

Java 25 parte con 1/64 della RAM e si ferma al 25%. Java 28 senza opzioni parte da 8 MB. I 2 MB di cui parla il JEP 545 arrivano con `-XX:+ZAdaptiveHeapSizing`, che porta anche il massimo a tutta la RAM.

### L'heap

Le mediane delle 5 corse, da `prove/confronto.txt`:

| Scenario | Prima risposta | Heap massimo | Heap alla fine | Secondi con P99 sopra 20 ms |
|---|---|---|---|---|
| `java25-heap-piccolo` | 3.223 ms | 7.806 MB | 7.806 MB | 1 su 130 |
| `java25-heap-grande` | 3.592 ms | 8.192 MB | 8.192 MB | 6 su 130 |
| `java28-heap-piccolo` | 3.391 ms | 7.834 MB | 7.834 MB | 5 su 130 |
| `java28-adattivo` | 3.189 ms | **1.022 MB** | 828 MB | 2 su 130 |

Con `-Xms2M -Xmx8G`, su Java 25 e su Java 28, l'heap cresce durante la rampa e tra il 22° e il 33° secondo supera i 7.000 MB, vicino agli 8 GB del massimo. Ci resta fino alla fine. Con l'opzione nuova l'heap si è fermato tra 976 e 1.450 MB nelle 5 corse, ed è finito tra 814 e 1.136 MB. Nella corsa di controllo, fatta dopo con il Mac ancora in swap, è arrivato a 1.442 MB (`prove/corsa-di-controllo.txt`), e in un'altra corsa singola dello stesso giorno a 2.146 MB. Il valore cambia con quello che succede sulla macchina, come descrive il JEP 546, ma è rimasto sempre sotto un terzo degli altri scenari.

PetClinic è la stessa e gli oggetti ancora in uso sono gli stessi: a regime, dopo ogni ciclo del GC restano tra 78 e 466 MB. Cambia quanto ZGC lascia crescere l'heap prima di raccogliere. Qualche riga dei log della seconda corsa, da `prove/gc/`:

```
java25-heap-piccolo-2
[31.739s][info][gc] GC(10) Minor Collection (High Usage) 7762M(95%)->126M(2%) 0.021s
[36.904s][info][gc] GC(11) Minor Collection (High Usage) 7754M(95%)->132M(2%) 0.022s
[45.691s][info][gc] GC(13) Minor Collection (High Usage) 7754M(95%)->136M(2%) 0.023s

java28-adattivo-2
[48.227s][info][gc] GC(99) Minor Collection (High Usage) 788M(91%)->136M(16%) 0.025s
[48.738s][info][gc] GC(100) Minor Collection (High Usage) 794M(91%)->136M(16%) 0.025s
[49.249s][info][gc] GC(101) Minor Collection (High Usage) 796M(91%)->140M(16%) 0.025s
```

In queste righe Java 25 lascia riempire l'heap fino a 7,6 GB, raccoglie ogni 5 secondi circa e dopo ogni ciclo restano circa 130 MB. Java 28 con l'heap adattivo lo lascia riempire fino a 800 MB, raccoglie ogni mezzo secondo e restano circa 130 MB anche lì. Le percentuali sono diverse perché nel primo log sono rispetto agli 8 GB del massimo, nel secondo rispetto all'heap di quel momento.

### Il prezzo: più cicli del GC

Un heap più piccolo si riempie prima, quindi ZGC lavora più spesso (`prove/cicli-gc.txt`):

```
cicli di ZGC in ogni corsa, avvio compreso, e loro durata sommata (da prove/gc)
java25-heap-piccolo: cicli 37 37 36 37 36, secondi 2,41 2,30 2,32 2,54 2,27
java25-heap-grande: cicli 36 38 34 36 38, secondi 2,33 2,39 2,45 2,29 2,36
java28-heap-piccolo: cicli 32 32 34 34 33, secondi 2,15 2,25 2,96 2,55 2,22
java28-adattivo: cicli 278 290 248 308 269, secondi 6,21 6,20 5,34 5,96 5,51
```

Con l'heap adattivo i cicli sono tra 248 e 308 invece di 32-38, e durano in tutto tra 5,34 e 6,21 secondi invece che tra 2,15 e 2,96, su poco più di 2 minuti di corsa. ZGC li fa girare insieme all'applicazione, con i suoi thread: non fermano le richieste, ma usano CPU. È lo scambio che il JEP 546 descrive, e la manopola è `-XX:ZGCIntensity` (5 di default): con un valore più basso ZGC lavora meno spesso e tiene un heap più grande, con uno più alto il contrario.

Non ho misurato la CPU usata dal processo, quindi non so dire quanto pesino quei secondi in più sul totale.

### Avvio e tempi di risposta

La prima risposta arriva tra 3,2 e 3,6 secondi in tutti gli scenari. Lo scenario con heap grande è il più lento a partire (3.592 ms di mediana) perché all'avvio la JVM tocca 8 GB: nel log del GC la prima riga arriva dopo 0,31-0,49 secondi, negli altri scenari dopo 4-10 millisecondi.

I secondi con P99 sopra i 20 ms, corsa per corsa (da `prove/confronto.txt`):

| Scenario | Corsa 1 | 2 | 3 | 4 | 5 | Totale su 650 |
|---|---|---|---|---|---|---|
| `java25-heap-piccolo` | 3 | 1 | 1 | 10 | 1 | 16 |
| `java25-heap-grande` | 12 | 2 | 40 | 6 | 1 | 61 |
| `java28-heap-piccolo` | 3 | 5 | 18 | 5 | 3 | 34 |
| `java28-adattivo` | 1 | 1 | 11 | 2 | 3 | 18 |

In tutte le 20 corse il primo secondo è sopra i 20 ms: è il secondo con le prime 49 richieste, a freddo.

Quello che il JEP 545 promette sul riscaldamento, da 80 a 10 secondi nel suo caso di studio, qui non si vede: con 600 richieste al secondo anche Java 25 con heap piccolo ha avuto il P99 sopra i 20 ms solo nel primo secondo, in 3 corse su 5. Il carico del JEP è 50 volte il mio: con molti più oggetti creati ogni secondo l'heap deve crescere di più e più in fretta, e immagino che sia lì che il JEP 545 fa la differenza. In più, su macOS ZGC non usa le pagine da 2 MB, che nel JEP valgono da sole una parte del guadagno.

L'heap adattivo ha tempi di risposta come Java 25 con heap piccolo, con un heap di 1.022 MB invece di 7.806.

### Lo swap del Mac

Tre scenari su quattro arrivano a 8 GB di heap, su un Mac da 16 GB dove girano anche l'editor e il browser. Lo scenario con gli 8 GB toccati all'avvio ha avuto i tempi peggiori: nella corsa 3, 40 secondi sopra i 20 ms e un P99 fino a 1.920 ms al secondo 126.

Dopo quella corsa ho cominciato a guardare lo swap con `vm_stat` e `sysctl vm.swapusage`, ogni 5 secondi fino alla fine (`prove/swap-mac.txt`). Questo campionamento l'ho fatto a mano, da un altro terminale, e non è in `prova.sh`. Pagine scritte nello swap durante ogni corsa:

| Scenario | Corsa 3 | Corsa 4 | Corsa 5 |
|---|---|---|---|
| `java25-heap-piccolo` | | 18.060 | 0 |
| `java25-heap-grande` | | 0 | 0 |
| `java28-heap-piccolo` | 86.404 | 68.712 | 24.720 |
| `java28-adattivo` | 0 | 0 | 0 |

Con heap da 8 GB macOS ha scritto pagine nello swap in 4 corse su 7, tutte con `-Xmx8G` e l'heap che cresceva da 2 MB. Con l'heap adattivo in nessuna, e lo swap usato è sceso. Per la corsa 3 con heap grande, la peggiore, lo swap non l'ho registrato, quindi non so quanto abbia pesato lì.

Lo swap però resta da una corsa all'altra, e le pagine che ci erano finite prima vanno rilette dal disco: probabilmente è per questo che anche la corsa 3 dell'heap adattivo ha 11 secondi sopra i 20 ms. I tempi di risposta di questa prova dipendono quindi anche da cosa è successo nelle corse prima, e non li uso per dire quale scenario è più veloce. L'heap invece è rimasto lo stesso in tutte le corse.

## Cosa non ho provato

- Linux, dove ZGC con Java 28 usa anche le pagine da 2 MB.
- Il carico del JEP, 30.000 richieste al secondo: PetClinic sul mio Mac ne regge poco più di 1.000.
- Un container con un limite di memoria. Con l'heap adattivo il massimo è quasi tutta la memoria che la JVM vede: in un container è il limite del container.
- Più JVM sulla stessa macchina, il caso di studio del JEP 546 (con SPECjbb, che ha una licenza a parte).
- `-XX:ZGCIntensity` con valori diversi da quello di default.
- La CPU usata dal processo.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA25...` o `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` nelle variabili `JAVA25` e `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `Sulla porta 8080 risponde già qualcosa` | Fermate il programma che usa la porta 8080, ad esempio un altro Spring Boot |
| `PetClinic si è fermata da sola` o `non risponde dopo 60 secondi` | Guardate il log indicato nel messaggio, in `target/log/` |
| `Unrecognized VM option 'ZAdaptiveHeapSizing'` | State usando un JDK 28 più vecchio della build 24 o un altro JDK: controllate `JAVA28` |
| Tanti secondi con P99 alto in tutti gli scenari | Il Mac è occupato da altro, o va in swap: guardate `memory_pressure` e chiudete i programmi che non servono |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 24 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |

## Pulizia

```sh
rm -rf ~/jdk target
```

Il `mvnw` di PetClinic lascia Maven in `~/.m2/wrapper/dists` e le librerie di Spring in `~/.m2/repository`: se non usate Maven per altro potete cancellare anche quelle cartelle.

## Link

- JEP 545, Faster Startup and Warmup with ZGC: https://openjdk.org/jeps/545
- JEP 546, Adaptive Heap Sizing for ZGC: https://openjdk.org/jeps/546
- Spring PetClinic: https://github.com/spring-projects/spring-petclinic
- JDK 28 early access: https://jdk.java.net/28/
- JDK 25.0.2 e le altre versioni: https://jdk.java.net/archive/
