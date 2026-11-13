import { DynamoDBClient, TransactWriteItemsCommand } from "@aws-sdk/client-dynamodb";
import { EventBridgeClient, PutEventsCommand } from "@aws-sdk/client-eventbridge";
import { PutObjectCommand, S3Client } from "@aws-sdk/client-s3";

const { TABELLA_CONTI, BUCKET_CONTABILI } = process.env;
const ORIGINE = "tried-it-first.banca";

const dynamo = new DynamoDBClient();
const eventBridge = new EventBridgeClient();
const s3 = new S3Client();

const aggiornamento = (conto, espressione, condizione, importo) => ({
    Update: {
        TableName: TABELLA_CONTI,
        Key: { conto: { S: conto } },
        UpdateExpression: espressione,
        ConditionExpression: condizione,
        ExpressionAttributeValues: { ":importo": { N: importo } },
    },
});

const prelievo = ({ da, importo }) =>
    aggiornamento(da, "SET saldo = saldo - :importo", "saldo >= :importo", importo);

const versamento = ({ a, importo }) =>
    aggiornamento(a, "SET saldo = saldo + :importo", "attribute_exists(conto)", importo);

const TRANSAZIONE = [
    { movimento: prelievo, motivoDelRifiuto: "saldo insufficiente" },
    { movimento: versamento, motivoDelRifiuto: "conto di arrivo inesistente" },
];

const sposta = (bonifico) => dynamo.send(new TransactWriteItemsCommand({
    ClientRequestToken: bonifico.id,
    TransactItems: TRANSAZIONE.map(({ movimento }) => movimento(bonifico)),
}));

const motivoDelRifiuto = (errore) => {
    if (errore.name !== "TransactionCanceledException") {
        return undefined;
    }
    const motivi = errore.CancellationReasons ?? [];
    const posizione = motivi.findIndex(({ Code }) => Code === "ConditionalCheckFailed");
    return TRANSAZIONE[posizione]?.motivoDelRifiuto;
};

const pubblica = async (tipo, dettaglio) => {
    const { FailedEntryCount, Entries } = await eventBridge.send(new PutEventsCommand({
        Entries: [{
            Source: ORIGINE,
            DetailType: tipo,
            Detail: JSON.stringify(dettaglio),
        }],
    }));
    if (FailedEntryCount > 0) {
        throw new Error(Entries[0].ErrorMessage);
    }
};

const contabile = ({ id, da, a, importo }) =>
    `Bonifico ${id}\nDal conto ${da} al conto ${a}\nImporto: ${importo} EUR\n`;

const esegui = async (bonifico) => {
    try {
        await sposta(bonifico);
    } catch (errore) {
        const motivo = motivoDelRifiuto(errore);
        if (motivo === undefined) {
            throw errore;
        }
        await pubblica("BonificoRifiutato", { ...bonifico, motivo });
        return;
    }
    await s3.send(new PutObjectCommand({
        Bucket: BUCKET_CONTABILI,
        Key: `contabili/${bonifico.id}.txt`,
        Body: contabile(bonifico),
        ContentType: "text/plain; charset=utf-8",
    }));
    await pubblica("BonificoEseguito", bonifico);
};

export const handler = async ({ Records }) => {
    for (const { body } of Records) {
        await esegui(JSON.parse(body));
    }
};
