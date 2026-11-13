import { SQSClient, SendMessageCommand } from "@aws-sdk/client-sqs";

const CODA_BONIFICI = process.env.CODA_BONIFICI;
const IDENTIFICATIVO = /^[A-Za-z0-9-]{1,36}$/;
const IMPORTO = /^\d{1,9}(\.\d{1,2})?$/;

const sqs = new SQSClient();

const risposta = (stato, corpo) => ({
    statusCode: stato,
    headers: { "content-type": "application/json" },
    body: JSON.stringify(corpo),
});

const leggi = (corpo) => {
    try {
        const bonifico = JSON.parse(corpo);
        return typeof bonifico === "object" && !Array.isArray(bonifico) ? bonifico : null;
    } catch {
        return null;
    }
};

const valido = (valore, formato) => typeof valore === "string" && formato.test(valore);

const controlla = ({ id, da, a, importo }) => {
    if (![id, da, a].every((valore) => valido(valore, IDENTIFICATIVO))) {
        return "id, da e a vogliono da 1 a 36 lettere, cifre o trattini";
    }
    if (da === a) {
        return "il conto di partenza e quello di arrivo coincidono";
    }
    if (!valido(importo, IMPORTO) || Number(importo) === 0) {
        return "importo non valido";
    }
    return null;
};

export const handler = async (evento) => {
    const bonifico = leggi(evento.body);
    if (bonifico === null) {
        return risposta(400, { errore: "il corpo non è un bonifico in JSON" });
    }
    const errore = controlla(bonifico);
    if (errore !== null) {
        return risposta(400, { errore });
    }

    const { id, da, a, importo } = bonifico;
    await sqs.send(new SendMessageCommand({
        QueueUrl: CODA_BONIFICI,
        MessageBody: JSON.stringify({ id, da, a, importo }),
        MessageGroupId: da,
        MessageDeduplicationId: id,
    }));
    return risposta(202, { id, stato: "in coda" });
};
