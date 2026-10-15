import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { setTimeout as attendi } from 'node:timers/promises';

const ATTESA_MS = 200;

const ambiente = randomUUID().slice(0, 8);
const avviatoAlle = Date.now();
let inCorso = 0;

const server = createServer(async (richiesta, risposta) => {
    inCorso++;
    const insieme = inCorso;
    await attendi(ATTESA_MS);
    inCorso--;

    risposta.writeHead(200, { 'content-type': 'application/json' });
    risposta.end(JSON.stringify({
        tipo: 'web',
        ambiente,
        insieme,
        vivoDaMs: Date.now() - avviatoAlle,
    }));
});

const endpoint = process.env.AWS_LAMBDA_HTTP_ENDPOINT;
const [host, porta] = endpoint.split(':');
server.listen(Number(porta), host);
