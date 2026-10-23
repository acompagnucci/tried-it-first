import { createRequire } from "node:module";
import { EventBridgeClient, PutEventsCommand } from "@aws-sdk/client-eventbridge";

const BUS = process.env.BUS;
const GIRI_MASSIMI = 50;
const VERSIONE_SDK = createRequire(import.meta.url)("@aws-sdk/client-eventbridge/package.json").version;

const eventBridge = new EventBridgeClient();

export const handler = async (evento) => {
    const { prova, giro } = evento.detail;
    console.log(`prova=${prova} giro=${giro} sdk=${VERSIONE_SDK} variabile=${process.env._X_AMZN_TRACE_ID}`);

    if (giro < GIRI_MASSIMI) {
        const risposta = await eventBridge.send(new PutEventsCommand({
            Entries: [{
                EventBusName: BUS,
                Source: "tried-it-first",
                DetailType: "giro",
                Detail: JSON.stringify({ prova, giro: giro + 1 }),
            }],
        }));
        if (risposta.FailedEntryCount > 0) {
            throw new Error(risposta.Entries[0].ErrorMessage);
        }
    }
};
