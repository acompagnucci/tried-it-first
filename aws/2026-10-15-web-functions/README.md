# Lambda Web Functions, con il tetto e senza

Il 12 ottobre 2026 AWS ha fatto uscire le [Lambda Web Functions](https://docs.aws.amazon.com/lambda/latest/dg/web-functions.html). Al posto di un handler si scrive un normale server HTTP in Node.js, e un ambiente serve più richieste insieme. La CPU si paga solo quando lavora, la memoria per tutto il tempo in cui l'ambiente resta acceso.

Ho voluto vedere come scala rispetto a una Lambda classica, quella che da ora AWS chiama Event Function, su un account con le quote di default. Lo stesso endpoint in tre versioni:

- Lambda classica con Function URL (da lunedì AWS la chiama Event Function)
- Web Function con al massimo 10 ambienti (`MaxEnvironments: 10`)
- Web Function senza tetto

Ogni richiesta aspetta 200 ms senza usare CPU, come se aspettasse una query, e risponde con un id dell'ambiente che l'ha servita e quante richieste quell'ambiente stava servendo in quel momento.

## Contenuto

```
codice.yaml          bucket S3 versionato per gli zip (le Web Functions leggono il codice solo da S3)
template.yaml        Lambda classica, Web Function e i due endpoint
web/index.js         la Web Function: un server node:http
classica/index.mjs   la Lambda classica: un handler
deploy.sh            crea i due stack e carica gli zip
misura.mjs           raffiche di richieste tutte insieme
carico.mjs           traffico costante per un certo numero di secondi
elimina.sh           cancella tutto, bucket compreso
prove/               i risultati delle mie esecuzioni e le metriche di CloudWatch
```

Nessuna dipendenza npm: solo moduli di Node.

## Cosa serve

| | Versione |
|---|---|
| Account AWS | uno di prova; la prova costa pochi centesimi |
| Regione | una dove ci sono le Web Functions. Io ho usato `eu-west-1` (Irlanda). Al lancio: Irlanda, Virginia, Ohio, Oregon, Tokyo |
| AWS CLI | 2.38.1 (servono i comandi `aws lambda-web`) |
| Node.js | 24 (io 24.22.0) |
| Sistema | macOS o Linux per gli script `.sh` |

L'utente AWS deve poter creare stack CloudFormation, ruoli IAM, bucket S3 e funzioni Lambda. Io ho usato un ruolo SSO con AdministratorAccess su un account vuoto.

Ho provato tutto su macOS 27, Apple M2 Pro, collegato da Roma. Le latenze che vedete sotto contengono il viaggio fino in Irlanda e ritorno.

## 1. Aggiorna gli strumenti

Una AWS CLI di qualche mese fa non conosce `lambda-web`. Su macOS, se l'avete installata col pacchetto ufficiale:

```sh
curl -o /tmp/AWSCLIV2.pkg https://awscli.amazonaws.com/AWSCLIV2.pkg
sudo installer -pkg /tmp/AWSCLIV2.pkg -target /
```

Per Linux: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html

Node 24 con nvm:

```sh
nvm install 24
nvm use 24
```

Verifica:

```sh
aws --version
aws lambda-web help | head -3
node --version
aws sts get-caller-identity
```

L'ultimo comando deve mostrare l'account su cui volete fare la prova.

## 2. Controlla le quote

```sh
aws lambda get-account-settings --region eu-west-1 --query AccountLimit.ConcurrentExecutions
```

Su un account nuovo è `10`: la Lambda classica non può avere più di 10 esecuzioni contemporanee. Io l'ho lasciato così. Le Web Functions hanno quote a parte (2000 vCPU per regione, 2 vCPU per ambiente).

## 3. Deploy

```sh
export AWS_REGION=eu-west-1
./deploy.sh
```

Crea lo stack `tried-it-first-codice` (il bucket), carica `web.zip` e `classica.zip` e crea lo stack `tried-it-first-web-functions`. Ci mette 2-3 minuti. Alla fine stampa i tre indirizzi:

```
|  UrlWeb          |  https://xxxxxxxxxxxxxxxx.xxx.lambda-web.eu-west-1.on.aws/   |
|  UrlClassica     |  https://xxxxxxxx.lambda-url.eu-west-1.on.aws/               |
|  UrlWebSenzaTetto|  https://xxxxxxxxxxxxxxxx.xxx.lambda-web.eu-west-1.on.aws/   |
```

Sono pubblici, senza autenticazione: chi ha l'indirizzo può chiamarli. Per questo alla fine si cancella tutto.

Controllo veloce:

```sh
curl <UrlClassica>
```

```
{"tipo":"classica","ambiente":"08b4b422","insieme":1,"vivoDaMs":209}
```

Non chiamate ancora gli indirizzi delle Web Functions, se volete vederle partire da freddo.

## 4. Le prove

Raffiche da 10, 50 e 100 richieste tutte insieme, con 3 secondi di pausa tra una e l'altra:

```sh
node misura.mjs <UrlClassica>
node misura.mjs <UrlWeb>
node misura.mjs <UrlWebSenzaTetto>
```

90 secondi con sempre 15 richieste aperte (appena una finisce ne parte un'altra), poi di nuovo le raffiche:

```sh
node carico.mjs <UrlWeb> 15 90
node misura.mjs <UrlWeb>
node carico.mjs <UrlWebSenzaTetto> 15 90
node misura.mjs <UrlWebSenzaTetto>
```

Ogni script stampa un riepilogo e salva il dettaglio di ogni richiesta in `prove/`.

### Le metriche

Le Web Functions mandano a CloudWatch quanto tempo sono rimasti accesi gli ambienti e quanta CPU hanno usato. I dati arrivano con qualche minuto di ritardo e restano anche dopo aver cancellato gli stack:

```sh
for m in EnvironmentActiveTime EnvironmentCPUTime; do
  aws cloudwatch get-metric-statistics --region eu-west-1 --namespace AWS/Lambda/Web \
    --metric-name $m --dimensions Name=FunctionName,Value=tried-it-first-web \
    --start-time <inizio, es. 2026-10-15T04:35:00Z> --end-time <fine> \
    --period 60 --statistics Sum --output table
done
```

Per il numero di ambienti di un endpoint: namespace `AWS/Lambda/Web/Usage`, metrica `EndpointActiveEnvironments`, dimensioni `FunctionName`, `EndpointName` (`prova` o `senza-tetto`) ed `ExecutionRegion`.

## Come è andata

Le mie esecuzioni sono in `prove/`, le metriche in `prove/metriche-cloudwatch.txt`.

### Raffiche a freddo

| | 10 insieme | 50 insieme | 100 insieme |
|---|---|---|---|
| Lambda classica | 10 ok | 10 ok, 40 × 429 | 10 ok, 90 × 429 |
| Web Function, tetto di 10 | 10 ok, 4 ambienti | 20 ok, 30 × 429 | 20 ok, 80 × 429 |
| Web Function senza tetto | 10 ok, 5 ambienti | 50 ok, 14 ambienti | 100 ok, 36 ambienti |

Io, prima della raffica, avevo fatto una sola richiesta di controllo con `curl` all'endpoint con il tetto; quello senza tetto l'ho aggiunto dopo e non l'avevo mai chiamato.

La classica risponde 429 con `ConcurrentInvocationLimitExceeded`: è la quota di 10 dell'account. La Web Function con il tetto risponde 429 con `Platform.EndpointMaxEnvThrottle`: 10 ambienti accesi, nessuno con posto libero.

Nessun ambiente ha servito più di 2 richieste insieme. Me ne aspettavo di più: la documentazione dice che `maxConcurrencyPerEnvironment` vale 64 di default. Leggendo meglio, 64 è solo il tetto per ambiente (si può impostare da 1 a 128 sulla revisione): quante richieste mandare davvero a ogni ambiente lo decide Lambda, guardando come l'applicazione usa le risorse, e all'inizio ne manda poche.

La prima raffica da 10 ha una mediana di 600-800 ms: lì c'è l'avvio degli ambienti.

### 90 secondi di traffico costante

`carico.mjs` tiene sempre 15 richieste aperte: appena una finisce ne parte un'altra. Ogni richiesta dura circa 250 ms, quindi sono circa 60 al secondo. Zero errori e mediana di 249 ms in tutti e due i casi.

| Secondi | Con il tetto | Senza tetto |
|---|---|---|
| 0-39 | 10 ambienti, al massimo 2 richieste insieme | 72 ambienti, 1 richiesta |
| 40-49 | 10 ambienti, 2 | 46 ambienti, 1 |
| 50-59 | 10 ambienti, 8 | 46 ambienti, 1 |
| 60-89 | 2 ambienti, 8 | 46 ambienti, 1 |

Con il tetto Lambda alza il limite per ambiente e spegne gli ambienti che non servono. Senza tetto tiene accesi molti più ambienti del necessario: la documentazione parla di capacità di riserva ("headroom") tenuta pronta per i picchi.

Subito dopo, le raffiche da 100 sono andate tutte a buon fine: con il tetto su 2 ambienti (uno ne ha servite 50 insieme), senza tetto su 15 (al massimo 7 insieme).

### Ambienti accesi e CPU

Dalle metriche di CloudWatch, nel minuto centrale di ciascun carico costante (06:40 con il tetto, 06:49 senza):

| | Ambienti accesi (somma) | CPU usata |
|---|---|---|
| Con il tetto | 360 s | 4,7 s |
| Senza tetto | 4.317 s | 19,1 s |

Le richieste servite erano le stesse, circa 600 ogni 10 secondi. Senza tetto gli ambienti sono rimasti accesi 12 volte tanto. La memoria si paga su quel tempo, la CPU solo sulla seconda colonna.

Una cosa che si vede nelle metriche: dopo la fine del traffico, con il tetto, 2-4 ambienti sono rimasti accesi per altri 5 minuti senza ricevere niente (dalle 06:42 alle 06:46). Anche quelli si pagano.

Il picco di memoria di un ambiente è stato 193 MB.

### Il tetto: sì o no

Io lo metto. Tiene a bada la spesa, e anche il database o il servizio che sta dietro: ogni ambiente apre le sue connessioni, e 72 ambienti sono 72 pool. AWS stessa consiglia un valore basso per i progetti non critici.

Il prezzo è quello della prima raffica: quando il traffico arriva tutto insieme e gli ambienti non hanno ancora alzato il loro limite, le richieste oltre il tetto prendono 429. E con un traffico che cresce nel tempo il tetto va tenuto d'occhio (`EndpointActiveEnvironments` vicino a `MaxEnvironments`) e alzato.

### Cosa non ho provato

- Il costo in euro. Il listino in Irlanda per le Web Functions non l'ho trovato, e su una prova di pochi minuti non avrebbe senso.
- Un traffico vero, che sale e scende. Ho provato solo raffiche e un carico piatto.
- Un'applicazione che usa CPU. Con un'attesa pura Lambda può mettere tante richieste nello stesso ambiente; con calcoli veri il limite dinamico si comporterà in un altro modo.
- Altre regioni, endpoint MultiRegion, `maxConcurrencyPerEnvironment` impostato a mano.
- Java: le Web Functions per ora vanno solo con Node.js.

I comandi per Linux non li ho eseguiti. Se qualcosa non torna, apritemi una issue.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Found invalid choice 'lambda-web'` | La AWS CLI è vecchia: aggiornatela (passo 1) |
| `Unrecognized resource types: [AWS::Lambda::WebFunction...]` | La regione non ha le Web Functions: usate `eu-west-1` o un'altra della tabella |
| `ExpiredToken` | Credenziali scadute: `aws sso login` o nuove chiavi |
| Le richieste alla Web Function falliscono subito dopo il deploy | Il server non si è messo in ascolto entro 10 secondi: controllate i log in `/aws/lambda/web/tried-it-first-web` |
| Tanti 429 anche sulla Web Function senza tetto | Guardate l'header `lambda-web-error-type` nel dettaglio in `prove/`: con `Platform.EndpointConcurrentInitThrottle` si stanno avviando troppi ambienti insieme |
| `node: command not found` o errori di sintassi | Serve Node 24: `nvm use 24` |

## Pulizia

```sh
./elimina.sh
```

Cancella lo stack delle funzioni, svuota il bucket (tutte le versioni dei file) e cancella lo stack del bucket. Controllo:

```sh
aws cloudformation list-stacks --region eu-west-1 --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE
aws lambda-web list-web-functions --region eu-west-1
```

Devono tornare vuoti. Alla fine lo script cancella anche i gruppi di log: a me Lambda ha ricreato `/aws/lambda/web/tried-it-first-web` mentre lo stack si stava cancellando, vuoto ma rimasto lì. Per controllare:

```sh
aws logs describe-log-groups --region eu-west-1 --log-group-name-prefix /aws/lambda/ --query 'logGroups[].logGroupName'
```

Le metriche di CloudWatch restano per conto loro e scadono da sole.

## Link

- Annuncio: https://aws.amazon.com/blogs/aws/announcing-aws-lambda-web-functions-for-fast-consistent-web-app-performance-anywhere/
- Documentazione: https://docs.aws.amazon.com/lambda/latest/dg/web-functions.html
- Scalabilità e tetto agli ambienti: https://docs.aws.amazon.com/lambda/latest/dg/web-functions-scaling.html
- Metriche: https://docs.aws.amazon.com/lambda/latest/dg/web-functions-monitoring-metrics.html
- Prezzi: https://aws.amazon.com/lambda/pricing/
