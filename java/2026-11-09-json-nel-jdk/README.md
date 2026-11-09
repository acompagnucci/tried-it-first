# Un bonifico in JSON con l'importo scritto due volte: Jackson e il parser di Java 28

Java 28, che uscirà a marzo 2027, avrà un parser JSON nel JDK, nel modulo `jdk.incubator.json` ([JEP 540](https://openjdk.org/jeps/540)). Legge un testo JSON e restituisce un albero di valori (`JsonObject`, `JsonArray`, `JsonNumber`...). Non trasforma il JSON in un record o in una classe, e il JEP dice che non vuole prendere il posto di Jackson o delle altre librerie.

Volevo vedere come si comporta con i JSON fatti male che prima o poi arrivano a un servizio, e confrontarlo con Jackson. Ho scritto sei file con un bonifico e li ho fatti leggere da tre programmi che riempiono lo stesso record:

```java
record Bonifico(String iban, BigDecimal importo, String causale)
```

| File | Cosa c'è dentro |
|---|---|
| `bonifico.json` | un bonifico normale, 250,00 € |
| `importo-doppio.json` | `"importo"` scritto due volte: 250.00 e, in fondo, 25000.00 |
| `importo-doppio-a-meta.json` | lo stesso, ma il secondo `"importo"` viene subito dopo il primo |
| `iban-doppio.json` | `"iban"` scritto due volte, il secondo in fondo con un altro IBAN |
| `importo-mancante.json` | manca `"importo"` |
| `virgola-in-piu.json` | una virgola dopo l'ultimo campo |

Gli IBAN sono finti. Lo standard JSON (RFC 8259) dice che i nomi dentro un oggetto "dovrebbero" essere unici, ma non lo impone: cosa fare con un doppione lo decide ogni parser.

| Programma | Libreria |
|---|---|
| `jdk28/LeggiBonifico.java` | `jdk.incubator.json` del JDK 28 |
| `jackson2/LeggiBonifico.java` | Jackson 2.23.0 |
| `jackson3/LeggiBonifico.java` | Jackson 3.3.0 |

Le due versioni di Jackson sono uscite tutte e due l'8 novembre 2026. I due programmi Jackson sono uguali, cambiano solo i package (`com.fasterxml.jackson` nella 2, `tools.jackson` nella 3), e girano in due modi: `normale`, con il mapper di default, e `severo`, con due opzioni in più.

## Contenuto

```
bonifici/                      i sei file JSON
jdk28/LeggiBonifico.java       legge i file con il parser di Java 28
jackson2/LeggiBonifico.java    li legge con Jackson 2.23.0, normale o severo
jackson3/LeggiBonifico.java    li legge con Jackson 3.3.0, normale o severo
scarica-jackson.sh             scarica i jar di Jackson da Maven Central in lib/
prova.sh                       lancia tutto e salva gli output in prove/
prove/                         output delle mie esecuzioni
```

Niente Maven o Gradle: ogni programma si lancia direttamente con `java`, e i jar di Jackson si passano con `--class-path`.

## Cosa serve

| | Versione |
|---|---|
| Sistema | macOS con Apple Silicon o Linux (gli script sono per `sh`); per Windows ci sono i comandi a mano più sotto |
| RAM | 1 GB basta |
| Disco | circa 450 MB per il JDK, 5 MB per i jar di Jackson |
| JDK | OpenJDK 28 early access, build 28-ea+23 del 5 novembre 2026 |
| Altro | `curl` e `shasum` (su macOS ci sono già) |

Ho provato tutto su macOS 27, Apple M2 Pro. I comandi per Linux e Windows non li ho eseguiti: se qualcosa non torna, apritemi una issue.

Le build early access non sono fatte per la produzione e cambiano ogni settimana. Il modulo poi è in incubazione: con le prossime build nomi e messaggi possono cambiare.

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
"$JAVA28" --list-modules | grep json
```

Deve comparire `openjdk version "28-ea"`, `build 28-ea+23` (o un numero di build più alto) e `jdk.incubator.json@28-ea`.

## 2. Scarica Jackson

```sh
./scarica-jackson.sh
```

Scarica in `lib/jackson2` e `lib/jackson3` tre jar per versione (`jackson-databind`, `jackson-core`, `jackson-annotations`) e controlla ognuno con lo SHA-1 pubblicato su Maven Central. Per ogni jar deve stampare `OK`.

Su Linux, se manca `shasum`, nello script sostituitelo con `sha1sum`.

Windows (PowerShell), dalla cartella di questo README:

```powershell
$m = "https://repo1.maven.org/maven2"
New-Item -ItemType Directory -Force lib/jackson2, lib/jackson3 | Out-Null
foreach ($p in "com/fasterxml/jackson/core/jackson-databind/2.23.0/jackson-databind-2.23.0.jar",
               "com/fasterxml/jackson/core/jackson-core/2.23.0/jackson-core-2.23.0.jar",
               "com/fasterxml/jackson/core/jackson-annotations/2.23/jackson-annotations-2.23.jar") {
    Invoke-WebRequest "$m/$p" -OutFile "lib/jackson2/$(Split-Path $p -Leaf)"
}
foreach ($p in "tools/jackson/core/jackson-databind/3.3.0/jackson-databind-3.3.0.jar",
               "tools/jackson/core/jackson-core/3.3.0/jackson-core-3.3.0.jar",
               "com/fasterxml/jackson/core/jackson-annotations/2.23/jackson-annotations-2.23.jar") {
    Invoke-WebRequest "$m/$p" -OutFile "lib/jackson3/$(Split-Path $p -Leaf)"
}
```

Jackson 3 usa ancora `jackson-annotations` 2.23: le annotazioni sono rimaste nella serie 2.

## 3. Lancia le prove

macOS e Linux, dalla cartella di questo README e nello stesso terminale dove avete impostato `JAVA28`:

```sh
./prova.sh
```

Ci mette pochi secondi. Lancia una volta il programma con il parser di Java 28 e ognuno dei due programmi Jackson due volte, `normale` e `severo`, sempre su tutti i file di `bonifici/`. Gli output completi vanno in `prove/`, e alla fine lo script stampa un riassunto senza le righe `at [Source: ...]` dei messaggi di Jackson.

Windows (PowerShell):

```powershell
$JAVA28 = "C:\jdk\jdk-28\bin\java.exe"
$file = Get-ChildItem bonifici/*.json | ForEach-Object FullName
& $JAVA28 --add-modules jdk.incubator.json jdk28/LeggiBonifico.java $file
foreach ($v in 2, 3) {
    foreach ($modo in "normale", "severo") {
        & $JAVA28 --class-path "lib/jackson$v/*" "jackson$v/LeggiBonifico.java" $modo $file
    }
}
```

## Risultato atteso

```
== prove/jdk28.txt
bonifico.json: Bonifico[iban=IT60X0542811101000000123456, importo=250.00, causale=Affitto novembre]
iban-doppio.json: errore: Duplicate member name: "iban" was already parsed. Path: "{". Location: line 4, position 2.
importo-doppio-a-meta.json: errore: Duplicate member name: "importo" was already parsed. Path: "{". Location: line 3, position 2.
importo-doppio.json: errore: Duplicate member name: "importo" was already parsed. Path: "{". Location: line 4, position 2.
importo-mancante.json: errore: JsonObject member "importo" does not exist. Path: "". Location: line 0, position 0.
virgola-in-piu.json: errore: Expecting a JSON Object member name. Path: "{". Location: line 4, position 0.
== prove/jackson2-normale.txt
bonifico.json: Bonifico[iban=IT60X0542811101000000123456, importo=250.00, causale=Affitto novembre]
iban-doppio.json: errore: No fallback setter/field defined for creator property 'iban' (of `jackson2.LeggiBonifico$Bonifico`) (through reference chain: jackson2.LeggiBonifico$Bonifico["iban"])
importo-doppio-a-meta.json: Bonifico[iban=IT60X0542811101000000123456, importo=25000.00, causale=Affitto novembre]
importo-doppio.json: errore: No fallback setter/field defined for creator property 'importo' (of `jackson2.LeggiBonifico$Bonifico`) (through reference chain: jackson2.LeggiBonifico$Bonifico["importo"])
importo-mancante.json: Bonifico[iban=IT60X0542811101000000123456, importo=null, causale=Affitto novembre]
virgola-in-piu.json: errore: Unexpected character ('}' (code 125)): was expecting double-quote to start field name
== prove/jackson3-normale.txt
bonifico.json: Bonifico[iban=IT60X0542811101000000123456, importo=250.00, causale=Affitto novembre]
iban-doppio.json: Bonifico[iban=IT99X9999999999000000999999, importo=250.00, causale=Affitto novembre]
importo-doppio-a-meta.json: Bonifico[iban=IT60X0542811101000000123456, importo=25000.00, causale=Affitto novembre]
importo-doppio.json: Bonifico[iban=IT60X0542811101000000123456, importo=25000.00, causale=Affitto novembre]
importo-mancante.json: Bonifico[iban=IT60X0542811101000000123456, importo=null, causale=Affitto novembre]
virgola-in-piu.json: errore: Unexpected character ('}' (code 125)): was expecting double-quote to start property name
== prove/jackson2-severo.txt
bonifico.json: Bonifico[iban=IT60X0542811101000000123456, importo=250.00, causale=Affitto novembre]
iban-doppio.json: errore: Duplicate field 'iban'
importo-doppio-a-meta.json: errore: Duplicate field 'importo'
importo-doppio.json: errore: Duplicate field 'importo'
importo-mancante.json: errore: Missing creator property 'importo' (index 1); `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES` enabled
virgola-in-piu.json: errore: Unexpected character ('}' (code 125)): was expecting double-quote to start field name
== prove/jackson3-severo.txt
bonifico.json: Bonifico[iban=IT60X0542811101000000123456, importo=250.00, causale=Affitto novembre]
iban-doppio.json: errore: Duplicate Object property "iban"
importo-doppio-a-meta.json: errore: Duplicate Object property "importo"
importo-doppio.json: errore: Duplicate Object property "importo"
importo-mancante.json: errore: Missing creator property 'importo' (index 1); `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES` enabled
virgola-in-piu.json: errore: Unexpected character ('}' (code 125)): was expecting double-quote to start property name
```

Deve uscire proprio questo. In `prove/jdk28.txt` c'è anche la prima riga `WARNING: Using incubator modules: jdk.incubator.json`, che la JVM stampa ogni volta che si usa un modulo in incubazione.

## Come è andata

| File | JDK 28 | Jackson 2.23.0 | Jackson 3.3.0 |
|---|---|---|---|
| `bonifico.json` | 250,00 € | 250,00 € | 250,00 € |
| `importo-doppio.json` | errore: chiave doppia | errore, ma non dice che la chiave è doppia | **25.000,00 €** |
| `importo-doppio-a-meta.json` | errore: chiave doppia | **25.000,00 €** | **25.000,00 €** |
| `iban-doppio.json` | errore: chiave doppia | errore, ma non dice che la chiave è doppia | **il secondo IBAN** |
| `importo-mancante.json` | errore: manca `importo` | **importo `null`** | **importo `null`** |
| `virgola-in-piu.json` | errore | errore | errore |

Jackson con il mapper di default, cioè `JsonMapper.builder().build()`.

### La chiave doppia

Il parser di Java 28 rifiuta ogni oggetto con un nome ripetuto, in qualunque punto sia:

```
importo-doppio.json: errore: Duplicate member name: "importo" was already parsed. Path: "{". Location: line 4, position 2.
```

Jackson 3.3 tiene l'ultimo valore e non avvisa: dal file con 250.00 e 25000.00 esce un `Bonifico` da 25.000,00 €, e da quello con due IBAN un bonifico verso il secondo.

Jackson 2.23 fa lo stesso quando il doppione arriva prima dell'ultimo campo del record (`importo-doppio-a-meta.json`: 25.000,00 €). Quando arriva in fondo, dopo `causale`, si ferma, ma con un errore che parla d'altro:

```
importo-doppio.json: errore: No fallback setter/field defined for creator property 'importo' (of `jackson2.LeggiBonifico$Bonifico`) (through reference chain: jackson2.LeggiBonifico$Bonifico["importo"])
```

La mia spiegazione, che viene dal messaggio e da come si comporta: con un record Jackson 2 raccoglie i valori dei campi, chiama il costruttore quando li ha tutti, e un valore che arriva dopo dovrebbe metterlo con un setter, che un record non ha. Quindi il risultato dipende dall'ordine dei campi nel JSON.

Se lo stesso JSON passa da due servizi che usano librerie diverse, e una tiene il primo valore mentre l'altra tiene l'ultimo, i due servizi vedono due importi diversi.

### Il campo mancante

Senza `"importo"` il parser di Java 28 si ferma su `bonifico.get("importo")`:

```
importo-mancante.json: errore: JsonObject member "importo" does not exist. Path: "". Location: line 0, position 0.
```

Jackson, sia 2 che 3, crea il `Bonifico` con `importo=null`, e il `null` salta fuori più avanti, con un `NullPointerException` al primo `add` o `compareTo`.

### Jackson con due opzioni in più

In `LeggiBonifico.java` di Jackson il modo `severo` accende due opzioni:

```java
case "severo" -> JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .build();
```

Con queste Jackson 2 e 3 rifiutano tutti i file tranne `bonifico.json`, con messaggi chiari (`Duplicate field 'importo'` nella 2, `Duplicate Object property "importo"` nella 3, `Missing creator property 'importo'` in tutte e due). Il controllo sui doppioni è spento di default perché costa: secondo la documentazione di `StreamReadFeature` aggiunge tra il 20 e il 30% al tempo di lettura. Non l'ho misurato.

`FAIL_ON_MISSING_CREATOR_PROPERTIES` copre il campo che manca, non quello scritto `"importo": null`: per quello c'è `FAIL_ON_NULL_CREATOR_PROPERTIES`, che non ho messo nella prova.

### Il codice

Con Jackson leggere il bonifico è una riga:

```java
mapper.readValue(testo, Bonifico.class)
```

Con il parser di Java 28 il record si riempie a mano, campo per campo:

```java
private static Bonifico leggi(String testo) {
    JsonValue bonifico = Json.parse(testo);
    return new Bonifico(
            bonifico.get("iban").asString(),
            importo(bonifico.get("importo")),
            bonifico.get("causale").asString());
}

private static BigDecimal importo(JsonValue valore) {
    if (valore instanceof JsonNumber numero) {
        return new BigDecimal(numero.toString());
    }
    throw new JsonValueException("importo non è un numero: " + valore);
}
```

`JsonNumber` ha `asInt()`, `asLong()` e `asDouble()`, ma non un metodo per avere un `BigDecimal`. Però `toString()` restituisce il numero come era scritto nel file (`250.00` resta `250.00`), quindi `new BigDecimal(numero.toString())` è esatto. Con `asDouble()` sui decimali si torna ai soliti problemi: 0.10 più 0.20 fa 0.30000000000000004. Questo l'ho provato a parte, non è nello script.

### Cosa non c'è nel parser di Java 28

- Niente conversione verso record o classi: si legge l'albero e si costruisce l'oggetto a mano.
- Niente lettura a pezzi di file grandi (streaming): `Json.parse` vuole tutto il testo, come `String` o `char[]`.
- Niente commenti o virgole finali nel JSON: il JEP le esclude.
- È un modulo in incubazione: va aggiunto con `--add-modules jdk.incubator.json`, sia a `javac` sia a `java`, e a ogni avvio la JVM stampa un `WARNING`.

I messaggi di errore dicono dove si trova il problema, con il percorso dentro il JSON e la posizione. Riga e colonna però partono da 0: `line 4, position 2` in `importo-doppio.json` è la riga 5, terza colonna, dove inizia il secondo `"importo"`.

Non ho fatto misure serie dei tempi di lettura, né del parser di Java 28 né di Jackson.

### Quanto pesa Jackson

| | Jar | Byte |
|---|---|---|
| Jackson 2.23.0 | `jackson-databind`, `jackson-core`, `jackson-annotations` | 2.404.400 |
| Jackson 3.3.0 | `jackson-databind`, `jackson-core`, `jackson-annotations` 2.23 | 2.744.949 |
| JDK 28 | nessuno | 0 |

## Sui JDK di oggi

Il parser JSON c'è solo da Java 28. Anche i due programmi Jackson li ho lanciati solo sul JDK 28; Jackson 3 chiede almeno il JDK 17.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Impostate JAVA28...` | Lo script vuole il percorso completo del comando `java` del JDK 28 nella variabile `JAVA28` |
| `JAVA28 non punta a un JDK 28` | Il percorso è quello di un altro JDK: controllate `"$JAVA28" -version` |
| `Mancano i jar di Jackson` | Lanciate prima `./scarica-jackson.sh` |
| `package jdk.incubator.json is not visible` | Manca `--add-modules jdk.incubator.json` nel comando |
| `package tools.jackson.core does not exist` o `com.fasterxml.jackson.core` | Il `--class-path` non trova i jar: lanciate i comandi dalla cartella di questo README e mettete `lib/jackson3/*` tra virgolette, altrimenti la shell espande l'asterisco |
| `Permission denied` su uno script | `chmod +x prova.sh scarica-jackson.sh`, oppure `sh prova.sh` |
| `curl` scarica una pagina di errore e `tar` si lamenta | La build 23 non è più sul sito: prendete l'ultima da https://jdk.java.net/28/ |

## Pulizia

```sh
rm -rf lib ~/jdk
```

Su Windows cancellate `lib` e `C:\jdk`.

## Link

- JEP 540, Simple JSON API (Incubator): https://openjdk.org/jeps/540
- JDK 28 early access: https://jdk.java.net/28/
- RFC 8259, The JavaScript Object Notation (JSON) Data Interchange Format: https://www.rfc-editor.org/rfc/rfc8259
- Jackson 3, `StreamReadFeature`: https://github.com/FasterXML/jackson-core/blob/3.x/src/main/java/tools/jackson/core/StreamReadFeature.java
