# Lo stack bloccato e il force rollback di CloudFormation

Il 22 ottobre 2026 AWS ha aggiunto a `continue-update-rollback` l'opzione `--force-rollback`. Quando uno stack è in `UPDATE_ROLLBACK_FAILED`, CloudFormation salta da solo le risorse che non riesce a riportare indietro, anche dentro gli stack annidati. Prima bisognava trovarle e scriverle una per una in `--resources-to-skip`.

Ho fatto due copie di uno stack con una coda SQS, un parametro SSM e uno stack annidato con un'altra coda. In tutte e due ho cancellato le code fuori da CloudFormation, facendo la parte del collega che pulisce dalla console, e ho lanciato un aggiornamento che le tocca, così gli stack sono finiti in `UPDATE_ROLLBACK_FAILED`. Uno l'ho sbloccato come si faceva prima, l'altro con `--force-rollback`. Poi ho rifatto il deploy e ho provato a sistemarli con un drift-aware change set.

In breve: il force rollback sblocca lo stack con un comando e senza sapere niente delle risorse, ma le code restano cancellate e al deploy successivo lo stack si blocca di nuovo. Il drift-aware change set le ricrea solo se si parte dallo stack annidato.

## Contenuto

```
template.yaml      lo stack: coda Bonifici, parametro Configurazione, stack annidato Notifiche
annidato.yaml      lo stack annidato: coda Avvisi
bucket.yaml        bucket S3 dove aws cloudformation package carica annidato.yaml
comune.sh          regione e tre funzioni usate dagli altri script
deploy.sh          crea il bucket, carica il template annidato e fa il deploy con il valore di Attesa
rompi.sh           cancella le due code con la CLI di SQS, fuori da CloudFormation
stato.sh           stato dello stack e delle risorse, compreso lo stack annidato
prova.sh           tutta la prova, passo per passo; a-mano o forzato
elimina.sh         cancella tutto
prove/             i risultati delle mie due esecuzioni, a-mano/ e forzato/
```

## Lo stack

| Risorsa | Tipo | Cosa dipende da `Attesa` |
|---|---|---|
| `Bonifici` | `AWS::SQS::Queue` | `VisibilityTimeout` |
| `Configurazione` | `AWS::SSM::Parameter` | il valore, `attesa=30` o `attesa=60` |
| `Notifiche` | `AWS::CloudFormation::Stack` | passa `Attesa` allo stack annidato |
| `Notifiche` → `Avvisi` | `AWS::SQS::Queue` | `VisibilityTimeout` |

Il primo deploy usa `Attesa=30`, l'aggiornamento `Attesa=60`: cambia una proprietà di tutte e due le code, quindi CloudFormation deve toccarle. Le code non hanno un nome fisso, lo genera CloudFormation, così due copie dello stack non si pestano i piedi.

Non ci sono ruoli IAM né funzioni Lambda: solo code vuote, un parametro SSM standard e due file YAML su S3, quindi la prova costa zero o quasi.

## Cosa serve

| | Versione |
|---|---|
| Account AWS | uno di prova |
| Regione | io ho usato `eu-south-1` (Milano); gli script la prendono da `AWS_REGION` |
| AWS CLI | una versione che conosca `--force-rollback` e `--deployment-mode` (io 2.38.1) |
| Sistema | macOS o Linux per gli script `.sh` |

L'utente AWS deve poter creare stack CloudFormation, bucket S3, code SQS e parametri SSM. Io ho usato un ruolo SSO con AdministratorAccess.

Ho provato tutto su macOS 27, Apple M2 Pro.

## 1. Controlla gli strumenti

```sh
aws --version
aws cloudformation continue-update-rollback help | grep -c -- --force-rollback
aws cloudformation create-change-set help | grep -c -- --deployment-mode
aws sts get-caller-identity
```

Il secondo e il terzo comando devono stampare un numero maggiore di zero. Se stampano `0`, la CLI è troppo vecchia: aggiornatela. L'ultimo deve mostrare l'account su cui volete fare la prova.

Se la regione è una di quelle da abilitare (Milano lo è):

```sh
aws account get-region-opt-status --region-name eu-south-1
```

Deve rispondere `ENABLED`.

## 2. La prova

```sh
export AWS_REGION=eu-south-1
./prova.sh a-mano
./prova.sh forzato
```

Ognuna ci mette una decina di minuti. Lavorano su due stack diversi, `tried-it-first-a-mano` e `tried-it-first-forzato`, ma il bucket è uno solo: per lanciarle insieme in due terminali, la seconda fatela partire quando la prima ha scritto `Successfully created/updated stack - tried-it-first-modelli`. Se parte prima, si ferma con `tried-it-first-modelli is in CREATE_IN_PROGRESS state and can not be updated` (mi è successo in Irlanda, dove il bucket non c'era ancora).

Cosa fa `prova.sh`, e il file dove salva lo stato dopo ogni passo:

1. deploy con `Attesa=30` → `01-creato.txt`
2. cancella le due code con `rompi.sh` → `02-code-cancellate.txt`
3. deploy con `Attesa=60`, che deve fallire → `03-bloccato.txt`
4. sblocca lo stack → `04-sbloccato.txt`
5. di nuovo il deploy con `Attesa=60` → `05-di-nuovo-bloccato.txt`
6. sblocca lo stack → `06-sbloccato.txt`
7. drift-aware change set con `Attesa=60` sullo stack principale → `07-change-set.txt` e `08-dopo-change-set.txt`
8. sblocca lo stack → `09-sbloccato.txt`
9. drift-aware change set sullo stack annidato, poi su quello principale → `10-change-set-annidato.txt`, `11-change-set.txt` e `12-riparato.txt`

Le due prove sono uguali tranne che nello sblocco:

```sh
# a-mano: ai passi 4 e 6
aws cloudformation continue-update-rollback --stack-name tried-it-first-a-mano \
    --resources-to-skip Bonifici

# a-mano: al passo 8, con il nome dello stack annidato
aws cloudformation continue-update-rollback --stack-name tried-it-first-a-mano \
    --resources-to-skip tried-it-first-a-mano-Notifiche-H5Y1XF8L6YOI.Avvisi

# forzato: sempre lo stesso comando
aws cloudformation continue-update-rollback --stack-name tried-it-first-forzato \
    --force-rollback
```

Il nome dello stack annidato lo genera CloudFormation, quindi cambia a ogni deploy: `prova.sh` lo ricava dall'ARN della risorsa `Notifiche`. I secondi di ogni sblocco finiscono in `tempi.txt`. Lo stato lo controllo ogni 5 secondi, quindi i tempi sono precisi a 5 secondi.

## Come è andata

Le mie esecuzioni, del 30 ottobre 2026 a Milano, sono in `prove/a-mano/` e `prove/forzato/`. Nei file ho sostituito l'ID dell'account con `<account>`. Le due prove hanno dato gli stessi stati a ogni passo, tranne dove scritto sotto.

Lo stesso giorno ho rilanciato tutto in Irlanda (`eu-west-1`) per ricontrollare gli script: stati uguali a quelli di Milano a ogni passo, sblocchi in 18, 18 e 80 secondi. Quei file non sono nella cartella.

### Lo stack bloccato

Il deploy con `Attesa=60` prova ad aggiornare `Bonifici`, che non c'è più, e fallisce. CloudFormation annulla gli aggiornamenti delle altre risorse e parte il rollback, che deve rimettere `Bonifici` a 30. Fallisce anche quello, e annulla di nuovo gli altri. Da `prove/forzato/03-bloccato.txt`, accorciato:

```
tried-it-first-forzato  UPDATE_ROLLBACK_FAILED  The following resource(s) failed to update: [Notifiche, Configurazione, Bonifici].
    Bonifici        UPDATE_FAILED   Resource handler returned message: "The specified queue does not exist. ...
    Configurazione  UPDATE_FAILED   Resource update cancelled
    Notifiche       UPDATE_FAILED   Resource update cancelled
```

La coda `Avvisi` dentro lo stack annidato non viene nemmeno toccata: l'aggiornamento di `Notifiche` è annullato prima di arrivarci. Delle tre risorse in `UPDATE_FAILED`, solo `Bonifici` è rotta davvero.

### Lo sblocco

| | `--resources-to-skip Bonifici` | `--force-rollback` |
|---|---|---|
| Cosa devo sapere | quale risorsa saltare | niente |
| Risorse saltate | `Bonifici` | `Bonifici` e `Configurazione` |
| `Configurazione` | rollback eseguito | saltata |
| Motivo nello stato dello stack | nessuno | `One or more resources were skipped during UpdateRollback...` |
| Tempo | 18 secondi | 18 secondi |

Il force rollback salta tutte le risorse in `UPDATE_FAILED`, anche `Configurazione`, che era lì solo perché il suo rollback era stato annullato. `Notifiche` invece la riprova, e torna a posto. Qui saltare `Configurazione` non fa danni: il suo aggiornamento era stato annullato prima di cambiare il valore, e il change set del passo 7 la trova `IN_SYNC`.

In compenso il force rollback lascia scritto nello stack che qualcosa è stato saltato. Con `--resources-to-skip` lo stato è `UPDATE_ROLLBACK_COMPLETE` e il motivo è vuoto; si vede solo guardando le singole risorse.

### Il deploy dopo

Lo stack è di nuovo `UPDATE_ROLLBACK_COMPLETE`, quindi accetta un deploy. Rilanciando lo stesso aggiornamento, `Bonifici` non c'è ancora e lo stack torna in `UPDATE_ROLLBACK_FAILED`, con gli stessi stati del passo 3 (`05-di-nuovo-bloccato.txt`). Lo sblocco (passo 6) è andato come la prima volta, in 17 secondi. Sbloccare, insomma, non ricrea niente: rimette lo stack in uno stato in cui accetta deploy e basta.

### La riparazione

Dopo un force rollback, AWS consiglia un drift-aware change set (`--deployment-mode REVERT_DRIFT`): confronta il template con lo stato reale delle risorse e le riporta come da template. Sullo stack principale (`07-change-set.txt`):

```
Add     Bonifici        DELETED
Modify  Configurazione  IN_SYNC
Modify  Notifiche       UNSUPPORTED
```

Vede che `Bonifici` è stata cancellata e la ricrea. Lo stack annidato invece risulta `UNSUPPORTED`, e dentro non guarda: la coda `Avvisi` è cancellata anche lei, ma il change set non lo sa. Eseguito, aggiorna `Notifiche`, lo stack annidato prova ad aggiornare `Avvisi` e fallisce. Rollback fallito anche lì, e lo stack principale è di nuovo bloccato, stavolta per colpa di `Notifiche` (`08-dopo-change-set.txt`).

Allo sblocco del passo 8 c'è stata la sorpresa. La coda `Bonifici` appena ricreata viene cancellata, con questo motivo (`09-sbloccato.txt`):

```
DELETE_IN_PROGRESS  Resource was originally deleted outside CloudFormation; deleting recreated resource to match resource state before deployment.
```

Il rollback di un drift-aware change set riporta le risorse a com'erano davvero prima del deploy, e prima del deploy la coda non c'era. Lo sblocco del passo 8 è durato 77 secondi con tutti e due i metodi: gran parte è la cancellazione della coda.

Al passo 8 il metodo vecchio vuole il nome dello stack annidato, `tried-it-first-a-mano-Notifiche-H5Y1XF8L6YOI.Avvisi`, che bisogna andare a cercare. Il force rollback è lo stesso comando di prima. Qui fa davvero risparmiare lavoro.

La riparazione che ha funzionato parte dal basso:

```sh
aws cloudformation create-change-set --stack-name tried-it-first-forzato-Notifiche-YQ91K6EWZGNC \
    --change-set-name ripara-annidato --use-previous-template \
    --parameters ParameterKey=Attesa,UsePreviousValue=true --deployment-mode REVERT_DRIFT

aws cloudformation create-change-set --stack-name tried-it-first-forzato \
    --change-set-name ripara-tutto --template-body file://pacchetto/template.yaml \
    --parameters ParameterKey=Attesa,ParameterValue=60 --deployment-mode REVERT_DRIFT
```

Ognuno seguito da `execute-change-set`. Sullo stack annidato il change set trova `Avvisi` `DELETED` e la ricrea; su quello principale ricrea `Bonifici`. Alla fine (`12-riparato.txt`) lo stack è `UPDATE_COMPLETE` e tutte e due le code esistono, con `VisibilityTimeout` 60.

### Cosa non ho provato

- La console e la CDK: tutto è fatto con la CLI.
- Risorse cambiate fuori da CloudFormation invece che cancellate.
- Altri tipi di risorse oltre a SQS e SSM, e stack annidati su più livelli.
- Un caso in cui il force rollback salta una risorsa già aggiornata al valore nuovo: qui `Configurazione` è stata saltata quando aveva ancora il valore vecchio.
- Altre regioni oltre a Milano e Irlanda.

I comandi per Linux non li ho eseguiti.

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `Unknown options: --force-rollback` o `--deployment-mode` | CLI troppo vecchia: aggiornatela (passo 1) |
| `InvalidClientTokenId` o `UnrecognizedClientException` su `eu-south-1` | La regione non è abilitata sull'account (passo 1) |
| `ExpiredToken` | Credenziali scadute: `aws sso login` o nuove chiavi |
| `Il deploy doveva fallire e invece è andato.` | CloudFormation ha aggiornato lo stack anche senza le code: il comportamento è cambiato rispetto al 30 ottobre 2026. Apritemi una issue |
| `tried-it-first-modelli is in CREATE_IN_PROGRESS state and can not be updated` | Avete lanciato le due prove insieme prima che il bucket fosse pronto: aspettate che la prima lo crei e rilanciate la seconda (passo 2) |
| `Mi aspettavo UPDATE_ROLLBACK_FAILED, lo stack è in ...` | Il deploy è fallito per un altro motivo. Lo trovate con `aws cloudformation describe-stack-events --stack-name tried-it-first-<modo>` |

## Pulizia

```sh
./elimina.sh
```

Cancella i due stack (con i loro stack annidati), svuota e cancella il bucket. Controllo:

```sh
aws cloudformation list-stacks --query "StackSummaries[?StackStatus!='DELETE_COMPLETE'].StackName"
aws sqs list-queues --queue-name-prefix tried-it-first
aws ssm describe-parameters --query "Parameters[?starts_with(Name, 'CFN-Configurazione')].Name"
```

Devono tornare vuoti (le code dopo un minuto: SQS ci mette un po' a cancellarle). Sul computer resta solo la cartella `pacchetto/`, creata da `deploy.sh`.

## Link

- Annuncio: https://aws.amazon.com/about-aws/whats-new/2026/10/aws-cloudformation-cdk/
- Continuare un rollback, con il force rollback: https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-continueupdaterollback.html
- Drift-aware change set: https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/drift-aware-change-sets.html
- `continue-update-rollback` nella CLI: https://docs.aws.amazon.com/cli/latest/reference/cloudformation/continue-update-rollback.html
