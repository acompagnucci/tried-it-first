# 1.000.000 USD su un limite di 50.000, e il controllo vede 0: i campi strict di Java 28

Java 28, che uscirà a marzo 2027, avrà nella JVM i campi strict, in preview ([JEP 539](https://openjdk.org/jeps/539)). Un campo strict non ha un valore di default: va assegnato prima che qualcuno lo legga, e se è `final` chi lo legge trova sempre lo stesso valore. Nel class file è un flag sul campo, `ACC_STRICT_INIT`, e la JVM lo fa rispettare in due modi:

- su un campo statico controlla a runtime, mentre la classe si inizializza: leggerlo prima che sia assegnato lancia un'eccezione;
- su un campo di istanza ci pensa il verifier, prima che il codice giri: il campo va assegnato prima della chiamata a `super()`, altrimenti la classe non viene caricata.

Il flag lo decide il compilatore, e nel sorgente non c'è un modificatore per chiederlo. Il JEP dice chiaramente che javac non cambia il modo di compilare le classi di oggi: in Java 28 il flag arriva con le value class ([JEP 401](https://openjdk.org/jeps/401)), anche loro in preview.

Volevo vedere cosa cambia su due bug che in Java si scrivono senza accorgersene e che oggi passano in silenzio, perché la JVM restituisce il valore di default del campo:

| Bug | Nel codice della prova | Cosa succede oggi |
|---|---|---|
| il costruttore della superclasse chiama un metodo che la sottoclasse sovrascrive | `Importo` controlla il limite di 50.000,00 per bonifico chiamando `centesimi()`, che `ImportoInValuta` sovrascrive | quando il controllo gira, il costruttore di `ImportoInValuta` non ha ancora assegnato `centesimi`, che vale 0: un bonifico da 1.000.000,00 USD passa |
| due classi che si leggono a vicenda nelle inizializzazioni statiche | `Banca.ABI` arriva da `Configurazione.abi()`, e `Configurazione.INTESTAZIONE_RICEVUTA` vale `"ABI " + Banca.ABI` | se la prima classe usata è `Banca`, `Configurazione` si inizializza mentre `Banca.ABI` è ancora `null`, e l'intestazione resta `ABI null` finché la JVM non si ferma |

Lo stesso codice gira in sei versioni:

| Versione | Sorgenti | JDK | Cosa cambia |
|---|---|---|---|
| `normali-25` | `normali/` | 25.0.2 | classi normali |
| `normali-28` | `normali/` | 28-ea+25 | le stesse classi, senza preview |
| `value-28` | `value/` | 28-ea+25 con `--enable-preview` | le quattro classi sono value class |
| `strict-28` | `normali/` | 28-ea+25 con `--enable-preview` | il bytecode di `normali-28` con il flag strict su `ImportoInValuta.centesimi`, `ImportoInValuta.valuta` e `Banca.ABI`, messo da `RendiStrict` |
| `corrette-25` | `corrette/` | 25.0.2 | i due bug corretti a mano |
| `corrette-strict-28` | `corrette/` | 28-ea+25 con `--enable-preview` | le classi corrette, con il flag strict sugli stessi tre campi |

E tre casi, ognuno in una JVM nuova, perché una classe si inizializza una volta sola:

| Caso | Cosa fa |
|---|---|
| `importo` | crea un `ImportoInValuta` da 1.000.000,00 USD, con il limite a 50.000,00 per bonifico |
| `banca-prima` | legge prima `Banca.ABI` e poi `Configurazione.INTESTAZIONE_RICEVUTA` |
| `configurazione-prima` | le legge al contrario |

## Il codice

Il controllo del limite (`normali/bonifico/Importo.java`):

```java
abstract class Importo {

    static final long LIMITE_IN_CENTESIMI = 5_000_000L;

    protected Importo() {
        if (centesimi() > LIMITE_IN_CENTESIMI) {
            throw new ImportoOltreIlLimite(centesimi(), valuta());
        }
    }

    abstract long centesimi();

    abstract String valuta();
}
```

e la sottoclasse (`normali/bonifico/ImportoInValuta.java`):

```java
final class ImportoInValuta extends Importo {

    private final long centesimi;
    private final String valuta;

    ImportoInValuta(long centesimi, String valuta) {
        this.centesimi = centesimi;
        this.valuta = valuta;
    }

    @Override
    long centesimi() {
        return centesimi;
    }
    ...
}
```

Nel costruttore di `ImportoInValuta` javac mette la chiamata a `super()` prima delle due assegnazioni, anche se non è scritta. Il costruttore di `Importo` gira quindi con `centesimi` ancora a 0.

Le due classi statiche (`normali/bonifico/Banca.java` e `normali/bonifico/Configurazione.java`):

```java
final class Banca {

    static final String ABI = Configurazione.abi();
    ...
}
```

```java
final class Configurazione {

    private static final String ABI_DI_PROVA = "00001";

    static final String INTESTAZIONE_RICEVUTA = "ABI " + Banca.ABI;
    ...
    static String abi() {
        return System.getProperty("banca.abi", ABI_DI_PROVA);
    }
}
```

`Banca.ABI` arriva da un metodo, quindi javac non la tratta come una costante da copiare nel codice di chi la usa: il valore si legge davvero dal campo, a runtime. L'ABI 00001 è finto.

### Le value class

In `value/` cambia solo la dichiarazione delle classi (`abstract value class Importo`, `value class ImportoInValuta`, `value class Banca`, `value class Configurazione`), più una riga in `Importo`:

```java
protected Importo() {
    super();
    if (centesimi() > LIMITE_IN_CENTESIMI) {
```

In una value class l'ordine cambia: se `super()` non è scritto, javac lo mette in fondo al costruttore, e prima di `super()` non si possono chiamare metodi dell'oggetto. Senza quella riga la compilazione si ferma:

```
Importo.java:8: error: reference to centesimi() may only appear after an explicit constructor invocation
```

In `ImportoInValuta`, dove `super()` non c'è, javac lo mette dopo le due assegnazioni, e quando il costruttore di `Importo` chiama `centesimi()` i due campi sono già assegnati.

`Banca` e `Configurazione` come value class non hanno molto senso, perché hanno solo campi statici. Le ho scritte così per vedere se javac marca strict anche i campi statici delle value class.

### La correzione a mano

In `corrette/` i due bug sono corretti come si può fare già con Java 25. In `ImportoInValuta` assegno i campi e poi chiamo `super()`, con i costruttori flessibili del [JEP 513](https://openjdk.org/jeps/513):

```java
ImportoInValuta(long centesimi, String valuta) {
    this.centesimi = centesimi;
    this.valuta = valuta;
    super();
}
```

In `Configurazione` l'intestazione non passa più da `Banca`:

```java
static final String INTESTAZIONE_RICEVUTA = "ABI " + abi();
```

### Il flag messo a mano

Su classi normali javac il flag non lo mette. Per vedere cosa fa la JVM con il bytecode che javac genera oggi, `strumenti/strumenti/RendiStrict.java` lo aggiunge con la ClassFile API del JDK:

```java
var bytes = classFile.transformClass(classe, (builder, elemento) -> {
    switch (elemento) {
        case ClassFileVersion _ -> builder.withVersion(
                ClassFile.latestMajorVersion(), ClassFile.PREVIEW_MINOR_VERSION);
        case FieldModel campo when campi.contains(campo.fieldName().stringValue()) ->
                builder.transformField(campo, conStrictInit(campo));
        default -> builder.with(elemento);
    }
});
```

Il flag vale solo nei class file di una versione in preview (72.65535), e per questo `RendiStrict` cambia anche la versione.

`RendiStrict` toglie anche le stack map (`StackMapsOption.DROP_STACK_MAPS`). Se gliele faccio rigenerare, la ClassFile API si rifiuta di scrivere un costruttore che assegna un campo strict dopo `super()`:

```
java.lang.IllegalArgumentException: Unset fields mismatch at bytecode offset 1 of method <init>(...)
```

Io volevo vedere la risposta del verifier della JVM. Le classi che modifico non hanno salti nel codice, quindi alla JVM le stack map non servono.

## Contenuto

```
comune/bonifico/Prova.java                 lancia un caso e stampa il risultato
comune/bonifico/Caso.java                  i tre casi
comune/bonifico/ImportoOltreIlLimite.java  l'eccezione del controllo del limite
comune/bonifico/Importi.java               formattazione degli importi
normali/bonifico/                          Importo, ImportoInValuta, Banca, Configurazione come classi normali
value/bonifico/                            le stesse quattro classi come value class
corrette/bonifico/                         le stesse quattro classi con i due bug corretti
strumenti/strumenti/RendiStrict.java       aggiunge il flag strict a campi di un file .class
prova.sh                                   compila le sei versioni, lancia i tre casi e salva tutto in prove/
prove/                                     output delle mie esecuzioni, flag dei campi e bytecode del costruttore letti con javap
```

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (lo script è per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 1 GB basta |
| Disco | circa 820 MB per i due JDK |
| JDK | OpenJDK 25.0.2 e OpenJDK 28 early access, build 28-ea+25 del 19 novembre 2026 |
| Altro | `curl` (su macOS c'è già) |

Ho provato tutto su macOS 27, Apple M2 Pro. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Al posto di OpenJDK 25.0.2 va bene qualsiasi JDK 25 che avete già (Temurin, Corretto, ...). Le build early access del 28 non sono fatte per la produzione e cambiano ogni settimana.

## 1. Scarica i JDK

Li mettiamo in una cartella `~/jdk`, senza installarli: il vostro `java` predefinito non cambia e alla fine basta cancellare la cartella.

macOS con Apple Silicon (M1, M2, ...):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/25/GPL/openjdk-28-ea+25_macos-aarch64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA25=~/jdk/jdk-25.0.2.jdk/Contents/Home/bin/java
export JAVA28=~/jdk/jdk-28.jdk/Contents/Home/bin/java
```

Per i Mac Intel non c'è una build early access del 28.

Linux (x64; su ARM sostituite `x64` con `aarch64`):

```sh
mkdir -p ~/jdk
curl -L https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
curl -L https://download.java.net/java/early_access/jdk28/25/GPL/openjdk-28-ea+25_linux-x64_bin.tar.gz | tar -xz -C ~/jdk
export JAVA25=~/jdk/jdk-25.0.2/bin/java
export JAVA28=~/jdk/jdk-28/bin/java
```

Windows: scaricate i due zip e scompattateli dove volete, ad esempio in `C:\jdk`:

- https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_windows-x64_bin.zip
- https://download.java.net/java/early_access/jdk28/25/GPL/openjdk-28-ea+25_windows-x64_bin.zip

Quando esce una build nuova del 28, quella vecchia di solito sparisce dal sito. Se il link risponde 404 prendete l'ultima da https://jdk.java.net/28/ e cambiate il numero della build nei comandi.

Verifica:

```sh
"$JAVA25" -version
"$JAVA28" -version
```

Devono comparire `openjdk version "25.0.2"` e `openjdk version "28-ea"` con `build 28-ea+25` (o un numero di build più alto).

## 2. Lancia la prova

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA25` e `JAVA28`:

```sh
./prova.sh
```

Lo script compila le sei versioni in `target/`, ciascuna con il `javac` del suo JDK, aggiunge il flag strict dove serve e lancia i tre casi per ogni versione, ognuno in una JVM nuova. Sul mio Mac ci mette circa 6 secondi. Stampa tutto e salva gli output in `prove/`, insieme ai flag dei campi (`prove/*-flag.txt`) e al bytecode del costruttore di `ImportoInValuta` (`prove/*-costruttore.txt`), letti con `javap`. Le righe `Note: ... uses preview features of Java SE 28.` di `javac` sono normali.

Le eccezioni con lo stack trace di `strict-28` sono il risultato che cercavo: lo script le salva e va avanti.

Un caso solo, dopo che lo script ha compilato:

```sh
"$JAVA28" --enable-preview -cp target/strict-28 bonifico.Prova banca-prima
```

Windows (PowerShell):

```powershell
$JAVA25 = "C:\jdk\jdk-25.0.2\bin"
$JAVA28 = "C:\jdk\jdk-28\bin"
$comune = (Get-ChildItem comune/bonifico/*.java).FullName
& "$JAVA28\javac.exe" -d target/strumenti strumenti/strumenti/RendiStrict.java
& "$JAVA25\javac.exe" -d target/normali-25 $comune (Get-ChildItem normali/bonifico/*.java).FullName
& "$JAVA28\javac.exe" -d target/normali-28 $comune (Get-ChildItem normali/bonifico/*.java).FullName
& "$JAVA28\javac.exe" --release 28 --enable-preview -d target/value-28 $comune (Get-ChildItem value/bonifico/*.java).FullName
& "$JAVA28\javac.exe" -d target/strict-28 $comune (Get-ChildItem normali/bonifico/*.java).FullName
& "$JAVA25\javac.exe" -d target/corrette-25 $comune (Get-ChildItem corrette/bonifico/*.java).FullName
& "$JAVA28\javac.exe" -d target/corrette-strict-28 $comune (Get-ChildItem corrette/bonifico/*.java).FullName
foreach ($v in "strict-28", "corrette-strict-28") {
    & "$JAVA28\java.exe" -cp target/strumenti strumenti.RendiStrict target/$v/bonifico/ImportoInValuta.class centesimi valuta
    & "$JAVA28\java.exe" -cp target/strumenti strumenti.RendiStrict target/$v/bonifico/Banca.class ABI
}
foreach ($c in "importo", "banca-prima", "configurazione-prima") {
    & "$JAVA25\java.exe" -cp target/normali-25 bonifico.Prova $c
    & "$JAVA28\java.exe" -cp target/normali-28 bonifico.Prova $c
    & "$JAVA28\java.exe" --enable-preview -cp target/value-28 bonifico.Prova $c
    & "$JAVA28\java.exe" --enable-preview -cp target/strict-28 bonifico.Prova $c
    & "$JAVA25\java.exe" -cp target/corrette-25 bonifico.Prova $c
    & "$JAVA28\java.exe" --enable-preview -cp target/corrette-strict-28 bonifico.Prova $c
}
```

## Risultato atteso

Il mio `prove/normali-28-importo.txt`:

```
Java 28-ea+25-2095, caso importo
bonifico di 1.000.000,00 USD, limite per bonifico 50.000,00
  accettato: 1.000.000,00 USD
```

`prove/strict-28-importo.txt`:

```
Java 28-ea+25-2095, caso importo
bonifico di 1.000.000,00 USD, limite per bonifico 50.000,00
Exception in thread "main" java.lang.VerifyError: All strict fields must be initialized before super(): 2 field(s) in bonifico/ImportoInValuta
Exception Details:
  Location:
    bonifico/ImportoInValuta.<init>(JLjava/lang/String;)V @1: invokespecial
  Reason:
    Invalid use of strict instance fields
  ...
(la JVM è uscita con codice 1)
```

e `prove/strict-28-banca-prima.txt`:

```
Java 28-ea+25-2095, caso banca-prima
prima Banca.ABI, poi Configurazione.INTESTAZIONE_RICEVUTA
Exception in thread "main" java.lang.ExceptionInInitializerError
	at bonifico.Banca.<clinit>(Banca.java:5)
	at bonifico.Prova.bancaPrima(Prova.java:50)
	at bonifico.Prova.esegui(Prova.java:32)
	at bonifico.Prova.main(Prova.java:25)
Caused by: java.lang.IllegalStateException: Strict static "ABI" is unset before first read in bonifico.Banca
	at bonifico.Configurazione.<clinit>(Configurazione.java:7)
	... 4 more
(la JVM è uscita con codice 1)
```

Qui non c'è niente di casuale: a ogni giro gli output devono essere gli stessi. Gli altri sono in `prove/`.

## Come è andata

### Il limite di 50.000 per bonifico

| Versione | Bonifico da 1.000.000,00 USD |
|---|---|
| `normali-25` | **accettato** |
| `normali-28` | **accettato** |
| `value-28` | rifiutato: `1.000.000,00 USD oltre il limite di 50.000,00 per bonifico` |
| `strict-28` | la classe non si carica: `VerifyError: All strict fields must be initialized before super(): 2 field(s) in bonifico/ImportoInValuta` |
| `corrette-25` | rifiutato |
| `corrette-strict-28` | rifiutato |

Con le classi normali il controllo del limite vede `centesimi` a 0 e lascia passare il bonifico, su Java 25 come su Java 28 senza preview. Non ci sono eccezioni né warning a runtime: l'unico segnale è un importo che non avrebbe dovuto passare.

Con le value class lo stesso sorgente di `ImportoInValuta` diventa un altro bytecode. Il costruttore delle classi normali, `prove/normali-28-costruttore.txt`:

```
  bonifico.ImportoInValuta(long, java.lang.String);
    Code:
         0: aload_0
         1: invokespecial #1                  // Method bonifico/Importo."<init>":()V
         4: aload_0
         5: lload_1
         6: putfield      #7                  // Field centesimi:J
         9: aload_0
        10: aload_3
        11: putfield      #13                 // Field valuta:Ljava/lang/String;
        14: return
```

e quello delle value class, `prove/value-28-costruttore.txt`:

```
  bonifico.ImportoInValuta(long, java.lang.String);
    Code:
         0: aload_0
         1: lload_1
         2: putfield      #1                  // Field centesimi:J
         5: aload_0
         6: aload_3
         7: putfield      #7                  // Field valuta:Ljava/lang/String;
        10: aload_0
        11: invokespecial #11                 // Method bonifico/Importo."<init>":()V
        14: return
```

Con le value class i campi vengono assegnati prima di entrare nel costruttore di `Importo`, e il controllo trova l'importo vero.

In `strict-28` il bytecode è quello delle classi normali, con in più il flag strict sui due campi: il verifier rifiuta la classe e la JVM non la carica. È il comportamento descritto dal JEP: un campo strict assegnato dopo `super()` è bytecode non valido.

In `corrette-strict-28` il codice corretto a mano assegna i campi prima di `super()`, e con il flag strict il verifier lo accetta.

### L'ABI nell'intestazione della ricevuta

| Versione | `banca-prima` | `configurazione-prima` |
|---|---|---|
| `normali-25` | **`ABI null`** | `ABI 00001` |
| `normali-28` | **`ABI null`** | `ABI 00001` |
| `value-28` | **`ABI null`** | `ABI 00001` |
| `strict-28` | `IllegalStateException: Strict static "ABI" is unset before first read in bonifico.Banca` | `ABI 00001` |
| `corrette-25` | `ABI 00001` | `ABI 00001` |
| `corrette-strict-28` | `ABI 00001` | `ABI 00001` |

Il risultato dipende da quale classe viene usata per prima. Con `banca-prima` la JVM inizializza `Banca`, che chiama `Configurazione.abi()`; per farlo inizializza `Configurazione`, che legge `Banca.ABI` mentre `Banca` è ancora a metà, e trova `null`. In `Banca.ABI` poi finisce il valore giusto, `00001`, ma l'intestazione è già stata calcolata. Con `configurazione-prima` l'ordine è l'altro e va tutto bene. In un'applicazione vera l'ordine dipende da quale codice gira per primo all'avvio.

Le value class non cambiano niente, perché javac non marca strict i campi statici. `prove/value-28-flag.txt`:

```
bonifico.Banca
  minor version: 65535
  major version: 72
  static final java.lang.String ABI;
    flags: (0x0018) ACC_STATIC, ACC_FINAL
...
bonifico.ImportoInValuta
  minor version: 65535
  major version: 72
  private final long centesimi;
    flags: (0x0812) ACC_PRIVATE, ACC_FINAL, ACC_STRICT_INIT
  private final java.lang.String valuta;
    flags: (0x0812) ACC_PRIVATE, ACC_FINAL, ACC_STRICT_INIT
```

Il JEP scrive che i compilatori marcano strict tutti i campi delle value class. Nella build 28-ea+25 javac lo fa solo con quelli di istanza.

Con il flag messo a mano su `Banca.ABI` la lettura a metà inizializzazione non restituisce più `null`: lancia `IllegalStateException`, che arriva come `ExceptionInInitializerError` alla prima riga che usa `Banca`. Con `configurazione-prima`, dove `Banca.ABI` viene assegnato prima di essere letto, il flag non dà nessun fastidio.

### Il warning di javac

Il JEP ricorda che da Java 21 javac ha un warning per il primo bug, `this-escape`. Qui, con `-Xlint:all`, non compare: `Importo` non è `public`. L'ho provato a parte con `public abstract class Importo`, e lì compare:

```
warning: [this-escape] possible 'this' escape before subclass is fully initialized
```

Per il secondo bug javac non ha nessun warning.

## Cosa non ho provato

Il JEP dice che il JIT considera fidati i campi `final` strict: dopo la prima lettura può riusare il valore senza rileggerlo dalla memoria. Non l'ho misurato, servirebbe JMH.

Il JEP dice anche che un campo `final` strict non si può modificare con la reflection (`IllegalAccessException` anche con `setAccessible`), e che `ObjectOutputStream` e `ObjectInputStream` lanciano `InvalidClassException` sulle classi con campi strict di istanza che non sono record. Neanche questo l'ho provato.

Con Java 26 e 27 non ho provato niente: il JEP 539 è entrato nella 28.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA25...` o `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` di ciascun JDK nelle variabili `JAVA25` e `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `value classes are a preview feature and are disabled by default` | Manca `--release 28 --enable-preview` a `javac` per `value/` |
| `class, interface, enum, or record expected` su `value class` | State compilando `value/` con un JDK più vecchio del 28 |
| `UnsupportedClassVersionError: Preview features are not enabled for ... (class file version 72.65535)` | Manca `--enable-preview` a `java` per `value-28`, `strict-28` o `corrette-strict-28`: le value class e `RendiStrict` producono class file in preview |
| `UnsupportedClassVersionError: ... only recognizes class file versions up to 69.0` | State lanciando con il JDK 25 una versione compilata con il 28 |
| `java.lang.VerifyError` o `IllegalStateException: Strict static` in `strict-28` | È il risultato atteso, vedi sopra |
| `Permission denied` su `prova.sh` | `chmod +x prova.sh`, oppure `sh prova.sh` |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 25 del 28 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |

## Pulizia

```sh
rm -rf ~/jdk target
```

Su Windows cancellate `C:\jdk` e la cartella `target`.

## Link

- JEP 539, Strict Field Initialization in the JVM (Preview): https://openjdk.org/jeps/539
- JEP 401, Value Objects (Preview): https://openjdk.org/jeps/401
- JEP 513, Flexible Constructor Bodies: https://openjdk.org/jeps/513
- JDK 28 early access: https://jdk.java.net/28/
- Javadoc della ClassFile API nella build early access: https://download.java.net/java/early_access/jdk28/docs/api/java.base/java/lang/classfile/ClassFile.html
