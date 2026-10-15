import { writeFile } from 'node:fs/promises';
import { setTimeout as attendi } from 'node:timers/promises';

const PAUSA_TRA_RAFFICHE_MS = 3000;

const [url, elencoRaffiche = '10,50,100'] = process.argv.slice(2);
if (!url) {
    console.error('Uso: node misura.mjs <url> [raffiche, es. 10,50,100]');
    process.exit(1);
}
const raffiche = elencoRaffiche.split(',').map(Number);

async function chiama() {
    const inizio = performance.now();
    try {
        const risposta = await fetch(url);
        const testo = await risposta.text();
        const ms = Math.round(performance.now() - inizio);
        if (risposta.status !== 200) {
            const errore = risposta.headers.get('lambda-web-error-type') ?? testo.slice(0, 80);
            return { stato: risposta.status, ms, errore };
        }
        return { stato: 200, ms, ...JSON.parse(testo) };
    } catch (e) {
        return { stato: 'rete', ms: Math.round(performance.now() - inizio), errore: e.cause?.code ?? e.message };
    }
}

function percentile(valori, p) {
    const ordinati = [...valori].sort((a, b) => a - b);
    return ordinati[Math.min(ordinati.length - 1, Math.floor(ordinati.length * p))];
}

function riassunto(n, risposte) {
    const ok = risposte.filter(r => r.stato === 200);
    const tempi = ok.map(r => r.ms);
    const stati = {};
    for (const r of risposte) {
        stati[r.stato] = (stati[r.stato] ?? 0) + 1;
    }
    const errori = [...new Set(risposte.filter(r => r.errore).map(r => r.errore))];
    return {
        richieste: n,
        stati,
        ambienti: new Set(ok.map(r => r.ambiente)).size,
        piuInsiemeInUnAmbiente: Math.max(0, ...ok.map(r => r.insieme)),
        msMediana: tempi.length ? percentile(tempi, 0.5) : null,
        msP95: tempi.length ? percentile(tempi, 0.95) : null,
        msMax: tempi.length ? Math.max(...tempi) : null,
        errori,
    };
}

const risultati = [];
for (const n of raffiche) {
    const risposte = await Promise.all(Array.from({ length: n }, chiama));
    const r = riassunto(n, risposte);
    risultati.push({ ...r, risposte });
    console.log(`${String(n).padStart(4)} richieste | stati ${JSON.stringify(r.stati)} | ambienti ${r.ambienti}`
        + ` | max insieme ${r.piuInsiemeInUnAmbiente} | mediana ${r.msMediana} ms | p95 ${r.msP95} ms | max ${r.msMax} ms`
        + (r.errori.length ? ` | errori ${r.errori.join(', ')}` : ''));
    await attendi(PAUSA_TRA_RAFFICHE_MS);
}

const tipo = risultati.flatMap(r => r.risposte).find(r => r.tipo)?.tipo ?? 'sconosciuto';
const file = `prove/${tipo}-${new Date().toISOString().replaceAll(':', '-').slice(0, 19)}.json`;
await writeFile(file, JSON.stringify({ url, quando: new Date().toISOString(), risultati }, null, 2));
console.log(`Dettaglio in ${file}`);
