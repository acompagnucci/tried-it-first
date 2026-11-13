import { DynamoDBClient, PutItemCommand } from "@aws-sdk/client-dynamodb";
import { GetObjectCommand, S3Client } from "@aws-sdk/client-s3";

const TABELLA_CONTABILI = process.env.TABELLA_CONTABILI;
const CARTELLA = "contabili/";
const ESTENSIONE = ".txt";

const dynamo = new DynamoDBClient();
const s3 = new S3Client();

const decodifica = (chiave) => decodeURIComponent(chiave.replaceAll("+", " "));

const leggi = async (bucket, chiave) => {
    const { Body } = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: chiave }));
    return Body.transformToString();
};

export const handler = async ({ Records }) => {
    for (const { s3: { bucket, object } } of Records) {
        const chiave = decodifica(object.key);
        const bonifico = chiave.slice(CARTELLA.length, -ESTENSIONE.length);
        const testo = await leggi(bucket.name, chiave);
        await dynamo.send(new PutItemCommand({
            TableName: TABELLA_CONTABILI,
            Item: {
                bonifico: { S: bonifico },
                file: { S: chiave },
                testo: { S: testo },
            },
        }));
    }
};
