# Un bonifico fermato a metà, su tre registri

In questi giorni The Clearing House, che negli Stati Uniti gestisce RTP e CHIPS, ha raccontato la sua rete di depositi tokenizzati: i saldi dei conti scritti su un registro condiviso tra le banche ("shared permissioned ledger"), collegato a RTP e CHIPS, con le prime banche dentro nel primo semestre 2027.

Volevo capire cosa cambia rispetto a un database. Ho preso due banche finte, A e B, con 10 conti ciascuna da 1.000 €, e ho messo lo stesso bonifico su tre registri:

- **uno**: un database Postgres per tutte e due le banche. Il bonifico è una transazione.
- **due**: un database per banca. Il bonifico è un addebito su A e un accredito su B, due scritture in due posti.
- **chain**: un contratto su una chain Ethereum locale (Anvil) che tiene i saldi dei conti di tutte e due. Ogni banca firma con il suo indirizzo e può muovere solo i conti suoi.

Poi tre prove: 1000 bonifici di fila, un bonifico da 100 € interrotto a metà, la banca B che prova a spostare soldi da un conto della banca A.

## Contenuto

```
compose.yaml               Postgres 18 e Anvil in Docker, più forge per compilare
contratto/src/Registro.sol il contratto: conti, saldi, bonifico
registri.mjs               i tre registri con la stessa interfaccia
prova.mjs                  le prove
prove/                     i risultati delle mie esecuzioni
```

## Cosa serve

| | Versione |
|---|---|
| Docker | Docker Desktop con Compose (io Docker 29.8, Compose 5.6) |
| Node.js | 24 (io 24.22.0) |
| Sistema | macOS o Linux |

Le immagini sono `postgres:18.4` e `ghcr.io/foundry-rs/foundry:v1.5.1` (Anvil e forge). Insieme sono circa 1 GB da scaricare. Se avete già un'altra versione di Postgres 18 potete mettere quella in `compose.yaml`.

Ho provato tutto su macOS 27, Apple M2 Pro, 16 GB di RAM.

## 1. Avvia Postgres e Anvil

```sh
docker compose up -d
```

Postgres ascolta sulla porta 55432 (password `prova`), Anvil sulla 8545. Se le porte sono occupate cambiatele in `compose.yaml` e in cima a `registri.mjs`.

Anvil parte con 10 indirizzi già carichi di ETH di prova. Io uso il primo come gestore del registro, il secondo come banca A e il terzo come banca B.

## 2. Compila il contratto

```sh
docker compose run --rm forge build
```

```
Compiling 1 files with Solc 0.8.28
Compiler run successful!
```

La prima volta forge scarica il compilatore. Il risultato va in `contratto/out/`.

## 3. Installa le dipendenze

```sh
npm install
```

Due pacchetti: `pg` per Postgres e `viem` per parlare con Anvil.

## 4. Prepara i tre registri

```sh
node prova.mjs uno prepara
node prova.mjs due prepara
node prova.mjs chain prepara
```

```
Pronto: un database per tutte e due le banche: banca A 10.000,00 €, banca B 10.000,00 €, totale 20.000,00 €
Pronto: un database per banca: banca A 10.000,00 €, banca B 10.000,00 €, totale 20.000,00 €
Pronto: un registro condiviso su una chain locale: banca A 10.000,00 €, banca B 10.000,00 €, totale 20.000,00 €
```

`uno` crea il database `registro`, `due` i database `banca_a` e `banca_b`, `chain` pubblica il contratto e apre i conti, una transazione per banca. Rilanciando `prepara` si riparte da zero.

## 5. 1000 bonifici di fila

```sh
node prova.mjs uno bonifici
node prova.mjs due bonifici
node prova.mjs chain bonifici
```

Bonifici da 10 € uno dopo l'altro, alternati da A verso B e da B verso A, così i saldi alla fine tornano quelli di partenza. Per la chain ogni bonifico aspetta la ricevuta della transazione. Il dettaglio di ogni giro finisce in `prove/`.

## 6. Il bonifico fermato a metà

```sh
node prova.mjs due interrompi
node prova.mjs due saldi
```

Il programma parte con un bonifico da 100 € dal conto 1 (banca A) al conto 101 (banca B) e si uccide da solo con `SIGKILL` subito dopo il primo passo:

- `uno`: dopo l'addebito, prima dell'accredito e del `COMMIT`
- `due`: dopo l'addebito sul database di A, prima dell'accredito su quello di B
- `chain`: dopo aver spedito la transazione ad Anvil, prima di aspettare la ricevuta

Fate lo stesso con `uno` e `chain`.

## 7. L'intruso

```sh
node prova.mjs chain intruso
```

La banca B firma un bonifico dal conto 1, che è della banca A. Prima lo simula, poi lo manda lo stesso.

## Risultati

Tutto l'output del mio giro è in `prove/uscita.txt`.

### 1000 bonifici

Tre giri per registro:

| | Giro 1 | Giro 2 | Giro 3 | Un bonifico, mediana per giro |
|---|---|---|---|---|
| Un database solo | 0,60 s | 0,62 s | 0,62 s | 0,56 · 0,58 · 0,59 ms |
| Un database per banca | 0,53 s | 0,53 s | 0,49 s | 0,50 · 0,47 · 0,46 ms |
| Chain locale | 1,57 s | 1,55 s | 1,55 s | 1,46 · 1,45 · 1,46 ms |

Un bonifico sul contratto costa 39.464 gas.

Il database per banca è il più veloce perché non apre una transazione: due `UPDATE` e basta. Mi aspettavo una chain molto più lenta. Ma Anvil è un nodo solo sul mio Mac, chiude un blocco appena arriva una transazione e non deve mettersi d'accordo con nessun altro nodo. Su una rete vera ogni bonifico aspetta di entrare in un blocco, e su Ethereum ne arriva uno ogni 12 secondi. Questo numero quindi non dice quanto va veloce una blockchain, solo quanto tempo prende il contratto su una macchina sola.

### Il bonifico fermato a metà

```
$ node prova.mjs uno saldi
un database per tutte e due le banche: banca A 10.000,00 €, banca B 10.000,00 €, totale 20.000,00 €

$ node prova.mjs due saldi
un database per banca: banca A 9.900,00 €, banca B 10.000,00 €, totale 19.900,00 €

$ node prova.mjs chain saldi
un registro condiviso su una chain locale: banca A 9.900,00 €, banca B 10.100,00 €, totale 20.000,00 €
```

- **Un database solo**: la connessione cade prima del `COMMIT` e Postgres butta via l'addebito. Il bonifico non è mai partito.
- **Un database per banca**: l'addebito su A è già scritto, l'accredito su B non arriva. Mancano 100 €.
- **Chain**: il bonifico è una transazione sola e il programma l'ha già spedita. Passa tutta, anche se nessuno aspetta la ricevuta. Se il programma morisse prima di spedirla non partirebbe niente.

Il caso del database per banca è fatto apposta così, senza niente intorno. Nella realtà quel buco lo chiudono i messaggi tra le banche, i conti di transito e le quadrature. Ma esistono proprio perché i registri sono due.

### L'intruso

```
La banca B prova a spostare 100,00 € dal conto 1, che è della banca A, al conto 101.
Rifiutato dal contratto: NonAutorizzato(0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC, 1)
La mando lo stesso: Bonifico 1 -> 101 fallito nel blocco 3005
```

L'indirizzo è quello della banca B. Mandata lo stesso, la transazione entra nel blocco ma viene annullata, e i saldi restano dove sono. Su Postgres la stessa regola si scrive con un utente per banca e la row level security: non l'ho provato.

### Allora quando basta un database

Il database unico fa quello che fa la chain, ed è più veloce. Il problema è chi lo tiene. Due banche concorrenti non mettono i loro conti nel database dell'altra: oggi tra loro c'è un terzo di cui si fidano tutte e due, come RTP e CHIPS negli Stati Uniti o TARGET della Banca centrale europea da noi. Il registro condiviso prova a dare lo stesso risultato con le banche che tengono ciascuna una copia e si controllano a vicenda, e con le regole, come "B non tocca i conti di A", scritte nel contratto.

Se il registro lo tiene già qualcuno di cui si fidano tutti, un database basta.

### Cosa non ho provato

- Più nodi, uno per banca. È lì che si vede se una banca può barare sulla sua copia. Con un nodo solo chi gestisce Anvil può riscrivere i saldi come l'amministratore di Postgres.
- Una rete con blocchi a tempo, permissioned o pubblica: i tempi sopra non valgono lì.
- Bonifici in parallelo. Sono tutti uno dopo l'altro.
- La rete di The Clearing House, che non è ancora aperta, e Quant, la tecnologia che hanno scelto.

I comandi li ho eseguiti solo su macOS. Se qualcosa non torna, apritemi una issue.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop non è avviato |
| `Bind for 0.0.0.0:55432 failed: port is already allocated` | La porta è occupata: cambiatela in `compose.yaml` e in `registri.mjs` |
| `ENOENT ... contratto/out/Registro.sol/Registro.json` | Il contratto non è compilato: passo 2 |
| `ENOENT ... contratto/out/indirizzo.txt` | Manca `node prova.mjs chain prepara` |
| `Bonifico ... fallito nel blocco` dopo aver riavviato Anvil | Anvil riparte vuoto a ogni riavvio: rilanciate `node prova.mjs chain prepara` |
| `violates check constraint "conti_saldo_check"` | Il conto non ha abbastanza soldi: rilanciate `prepara` |

## Pulizia

```sh
docker compose down
```

Ferma e cancella i due container, con i database e la chain. Le immagini restano: `docker image rm ghcr.io/foundry-rs/foundry:v1.5.1` per togliere quella di Foundry, e lo stesso con `postgres:18.4` se non vi serve per altro.

## Link

- The Clearing House e i depositi tokenizzati: https://www.tokenpost.com/news/technology/32715
- Anvil: https://getfoundry.sh/anvil
- viem: https://viem.sh
- Row level security in Postgres: https://www.postgresql.org/docs/18/ddl-rowsecurity.html
