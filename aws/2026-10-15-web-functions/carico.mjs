import { writeFile } from 'node:fs/promises';

const [url, inVolo = '15', secondi = '90'] = process.argv.slice(2);
if (!url) {
    console.error('Uso: node carico.mjs <url> [richieste sempre in volo] [secondi]');
    process.exit(1);
}

const fine = Date.now() + Number(secondi) * 1000;
const inizio = Date.now();
const campioni = [];

async function lavoratore() {
    while (Date.now() < fine) {
        const partita = performance.now();
        const risposta = await fetch(url);
        const testo = await risposta.text();
        const campione = {
            secondo: Math.floor((Date.now() - inizio) / 1000),
            stato: risposta.status,
            ms: Math.round(performance.now() - partita),
        };
        if (risposta.status === 200) {
            const { ambiente, insieme } = JSON.parse(testo);
            Object.assign(campione, { ambiente, insieme });
        }
        campioni.push(campione);
    }
}

await Promise.all(Array.from({ length: Number(inVolo) }, lavoratore));

const finestre = Map.groupBy(campioni, c => Math.floor(c.secondo / 10) * 10);
console.log('secondi | richieste | 429 | ambienti | max insieme | mediana ms');
for (const [da, gruppo] of finestre) {
    const ok = gruppo.filter(c => c.stato === 200);
    const tempi = ok.map(c => c.ms).sort((a, b) => a - b);
    console.log(`${String(da).padStart(3)}-${String(da + 9).padEnd(3)} | ${String(gruppo.length).padStart(9)}`
        + ` | ${String(gruppo.filter(c => c.stato === 429).length).padStart(3)}`
        + ` | ${String(new Set(ok.map(c => c.ambiente)).size).padStart(8)}`
        + ` | ${String(Math.max(0, ...ok.map(c => c.insieme))).padStart(11)}`
        + ` | ${tempi[Math.floor(tempi.length / 2)] ?? '-'}`);
}

const file = `prove/carico-${inVolo}-${new Date().toISOString().replaceAll(':', '-').slice(0, 19)}.json`;
await writeFile(file, JSON.stringify({ url, inVolo: Number(inVolo), secondi: Number(secondi), campioni }, null, 2));
console.log(`Dettaglio in ${file}`);
