import { randomUUID } from 'node:crypto';
import { setTimeout as attendi } from 'node:timers/promises';

const ATTESA_MS = 200;

const ambiente = randomUUID().slice(0, 8);
const avviatoAlle = Date.now();
let inCorso = 0;

export const handler = async () => {
    inCorso++;
    const insieme = inCorso;
    await attendi(ATTESA_MS);
    inCorso--;

    return {
        statusCode: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
            tipo: 'classica',
            ambiente,
            insieme,
            vivoDaMs: Date.now() - avviatoAlle,
        }),
    };
};
