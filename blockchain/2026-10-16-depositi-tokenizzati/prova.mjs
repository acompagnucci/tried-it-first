import { writeFile } from 'node:fs/promises';
import { CONTI_A, CONTI_B, SALDO_INIZIALE, registri } from './registri.mjs';

const BONIFICI_DI_DEFAULT = 1000;
const IMPORTO_DEI_BONIFICI = 1000;
const IMPORTO_DELLA_PROVA = 10_000;
const CONTO_DI_A = CONTI_A[0];
const CONTO_DI_B = CONTI_B[0];
const ULTIMI_EVENTI = 5;
const PROVE = new URL('./prove/', import.meta.url);

const USO = `Uso:
  node prova.mjs <uno|due|chain> prepara
  node prova.mjs <uno|due|chain> bonifici [quanti, default ${BONIFICI_DI_DEFAULT}]
  node prova.mjs <uno|due|chain> interrompi
  node prova.mjs <uno|due|chain> saldi
  node prova.mjs chain intruso
  node prova.mjs chain storico [conto]`;

const [quale, comando, argomento] = process.argv.slice(2);
const registro = registri[quale];
const numero = argomento === undefined ? undefined : Number(argomento);
if (!registro || !comando || (numero !== undefined && !(Number.isInteger(numero) && numero > 0))) {
    console.error(USO);
    process.exit(1);
}

const euro = centesimi => (centesimi / 100).toLocaleString('it-IT', { minimumFractionDigits: 2, useGrouping: 'always' });

function stampaSaldi(titolo, saldi) {
    console.log(`${titolo}: banca A ${euro(saldi.A)} €, banca B ${euro(saldi.B)} €, totale ${euro(saldi.A + saldi.B)} €`);
}

function percentile(valori, p) {
    const ordinati = [...valori].sort((x, y) => x - y);
    return ordinati[Math.min(ordinati.length - 1, Math.floor(ordinati.length * p))];
}

function coppiaDelBonifico(i) {
    const coppia = Math.floor(i / 2) % CONTI_A.length;
    return i % 2 === 0 ? [CONTI_A[coppia], CONTI_B[coppia]] : [CONTI_B[coppia], CONTI_A[coppia]];
}

async function bonifici(quanti) {
    const prima = await registro.saldi();
    const tempi = [];
    const inizio = performance.now();
    for (let i = 0; i < quanti; i++) {
        const [da, a] = coppiaDelBonifico(i);
        const t = performance.now();
        await registro.bonifico(da, a, IMPORTO_DEI_BONIFICI);
        tempi.push(performance.now() - t);
    }
    const totaleMs = performance.now() - inizio;
    const dopo = await registro.saldi();

    const risultato = {
        registro: registro.nome,
        bonifici: quanti,
        secondi: Number((totaleMs / 1000).toFixed(2)),
        alSecondo: Math.round(quanti / (totaleMs / 1000)),
        msMediana: Number(percentile(tempi, 0.5).toFixed(2)),
        msP95: Number(percentile(tempi, 0.95).toFixed(2)),
        gasMediana: registro.gas ? percentile(registro.gas, 0.5) : undefined,
        prima,
        dopo,
    };
    const gas = risultato.gasMediana ? `, gas ${risultato.gasMediana}` : '';
    console.log(`${registro.nome}: ${quanti} bonifici in ${risultato.secondi} s (${risultato.alSecondo} al secondo),`
        + ` mediana ${risultato.msMediana} ms, p95 ${risultato.msP95} ms${gas}`);
    stampaSaldi('Prima', prima);
    stampaSaldi('Dopo ', dopo);

    const quando = new Date().toISOString();
    const file = `${quale}-bonifici-${quando.replaceAll(':', '-').slice(0, 19)}.json`;
    await writeFile(new URL(file, PROVE), JSON.stringify({ quando, ...risultato, tempi }, null, 2));
    console.log(`Dettaglio in prove/${file}`);
}

async function interrompi() {
    stampaSaldi('Prima', await registro.saldi());
    console.log(`Bonifico di ${euro(IMPORTO_DELLA_PROVA)} € dal conto ${CONTO_DI_A} (banca A) al conto ${CONTO_DI_B} (banca B). Fermo il programma a metà.`);
    await registro.bonifico(CONTO_DI_A, CONTO_DI_B, IMPORTO_DELLA_PROVA, { aMeta: true });
}

async function intruso() {
    console.log(`La banca B prova a spostare ${euro(IMPORTO_DELLA_PROVA)} € dal conto ${CONTO_DI_A}, che è della banca A, al conto ${CONTO_DI_B}.`);
    try {
        await registro.simula(CONTO_DI_A, CONTO_DI_B, IMPORTO_DELLA_PROVA, 'B');
        console.log('La simulazione è passata.');
    } catch (e) {
        const errore = e.walk?.(causa => causa.data?.errorName)?.data;
        if (!errore) {
            throw e;
        }
        console.log(`Rifiutato dal contratto: ${errore.errorName}(${errore.args.join(', ')})`);
    }
    try {
        await registro.bonifico(CONTO_DI_A, CONTO_DI_B, IMPORTO_DELLA_PROVA, { chiFirma: 'B' });
        console.log('Il bonifico è passato.');
    } catch (e) {
        console.log(`La mando lo stesso: ${e.message}`);
    }
}

async function storico(conto) {
    const eventi = await registro.storico(conto);
    if (conto === undefined) {
        console.log(`Eventi Bonifico sulla chain: ${eventi.length}.`);
    } else {
        const totale = lato => eventi.filter(e => e[lato] === conto).reduce((somma, e) => somma + e.centesimi, 0);
        const uscite = totale('da');
        const entrate = totale('a');
        console.log(`Eventi Bonifico del conto ${conto}: ${eventi.length}.`);
        console.log(`Saldo ricostruito dagli eventi: ${euro(SALDO_INIZIALE)} - ${euro(uscite)} + ${euro(entrate)} = ${euro(SALDO_INIZIALE - uscite + entrate)} €`);
        console.log(`Saldo nel contratto: ${euro(await registro.saldoDi(conto))} €`);
    }
    if (eventi.length > ULTIMI_EVENTI) {
        console.log(`Gli ultimi ${ULTIMI_EVENTI}:`);
    }
    for (const e of eventi.slice(-ULTIMI_EVENTI)) {
        console.log(`  blocco ${e.blocco}: dal conto ${e.da} al conto ${e.a}, ${euro(e.centesimi)} €`);
    }
}

async function esegui() {
    if (comando === 'prepara') {
        await registro.prepara();
        await registro.apri();
        stampaSaldi(`Pronto: ${registro.nome}`, await registro.saldi());
        return;
    }
    await registro.apri();
    if (comando === 'bonifici') {
        await bonifici(numero ?? BONIFICI_DI_DEFAULT);
    } else if (comando === 'saldi') {
        stampaSaldi(registro.nome, await registro.saldi());
    } else if (comando === 'interrompi') {
        await interrompi();
    } else if (comando === 'intruso' && registro.simula) {
        await intruso();
    } else if (comando === 'storico' && registro.storico) {
        await storico(numero);
    } else {
        console.error(USO);
        process.exitCode = 1;
    }
}

await esegui();
await registro.chiudi?.();
