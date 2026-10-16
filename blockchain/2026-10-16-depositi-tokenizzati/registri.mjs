import { readFile, writeFile } from 'node:fs/promises';
import pg from 'pg';
import { createPublicClient, createWalletClient, http } from 'viem';
import { foundry } from 'viem/chains';

const POSTGRES = 'postgres://postgres:prova@localhost:55432';
const ANVIL = 'http://localhost:8545';
const CONTRATTO = new URL('./contratto/out/Registro.sol/Registro.json', import.meta.url);
const FILE_INDIRIZZO = new URL('./contratto/out/indirizzo.txt', import.meta.url);
const ADDEBITO = 'UPDATE conti SET saldo = saldo - $2 WHERE numero = $1';
const ACCREDITO = 'UPDATE conti SET saldo = saldo + $2 WHERE numero = $1';

export const CONTI_A = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
export const CONTI_B = [101, 102, 103, 104, 105, 106, 107, 108, 109, 110];
export const SALDO_INIZIALE = 100_000;

export const bancaDi = conto => (CONTI_A.includes(conto) ? 'A' : 'B');

async function connetti(database) {
    const client = new pg.Client(`${POSTGRES}/${database}`);
    await client.connect();
    return client;
}

async function ricreaDatabase(nome) {
    const admin = await connetti('postgres');
    const database = admin.escapeIdentifier(nome);
    await admin.query(`DROP DATABASE IF EXISTS ${database} WITH (FORCE)`);
    await admin.query(`CREATE DATABASE ${database}`);
    await admin.end();
}

function interrompi() {
    process.kill(process.pid, 'SIGKILL');
}

export const unDatabase = {
    nome: 'un database per tutte e due le banche',

    async prepara() {
        await ricreaDatabase('registro');
        const db = await connetti('registro');
        const conti = [...CONTI_A, ...CONTI_B];
        await db.query('CREATE TABLE conti (numero int PRIMARY KEY, banca char(1) NOT NULL, saldo bigint NOT NULL CHECK (saldo >= 0))');
        await db.query('INSERT INTO conti SELECT numero, banca, $3 FROM unnest($1::int[], $2::char[]) AS c(numero, banca)',
            [conti, conti.map(bancaDi), SALDO_INIZIALE]);
        await db.end();
    },

    async apri() {
        this.db = await connetti('registro');
    },

    async bonifico(da, a, centesimi, { aMeta } = {}) {
        await this.db.query('BEGIN');
        try {
            await this.db.query(ADDEBITO, [da, centesimi]);
            if (aMeta) {
                interrompi();
            }
            await this.db.query(ACCREDITO, [a, centesimi]);
            await this.db.query('COMMIT');
        } catch (e) {
            await this.db.query('ROLLBACK');
            throw e;
        }
    },

    async saldi() {
        const { rows } = await this.db.query('SELECT banca, sum(saldo)::int AS totale FROM conti GROUP BY banca');
        return Object.fromEntries(rows.map(r => [r.banca, r.totale]));
    },

    async chiudi() {
        await this.db.end();
    },
};

async function preparaBanca(database, conti) {
    const db = await connetti(database);
    await db.query('CREATE TABLE conti (numero int PRIMARY KEY, saldo bigint NOT NULL CHECK (saldo >= 0))');
    await db.query('INSERT INTO conti SELECT numero, $2 FROM unnest($1::int[]) AS numero', [conti, SALDO_INIZIALE]);
    await db.end();
}

export const dueDatabase = {
    nome: 'un database per banca',

    async prepara() {
        await ricreaDatabase('banca_a');
        await ricreaDatabase('banca_b');
        await preparaBanca('banca_a', CONTI_A);
        await preparaBanca('banca_b', CONTI_B);
    },

    async apri() {
        this.db = { A: await connetti('banca_a'), B: await connetti('banca_b') };
    },

    async bonifico(da, a, centesimi, { aMeta } = {}) {
        await this.db[bancaDi(da)].query(ADDEBITO, [da, centesimi]);
        if (aMeta) {
            interrompi();
        }
        await this.db[bancaDi(a)].query(ACCREDITO, [a, centesimi]);
    },

    async saldi() {
        const totale = async db => (await db.query('SELECT sum(saldo)::int AS totale FROM conti')).rows[0].totale;
        return { A: await totale(this.db.A), B: await totale(this.db.B) };
    },

    async chiudi() {
        await this.db.A.end();
        await this.db.B.end();
    },
};

const chain = createPublicClient({ chain: foundry, transport: http(ANVIL), pollingInterval: 5 });
const portafoglio = account => createWalletClient({ account, chain: foundry, transport: http(ANVIL) });

export const registroCondiviso = {
    nome: 'un registro condiviso su una chain locale',

    async prepara() {
        const { abi, bytecode } = JSON.parse(await readFile(CONTRATTO, 'utf8'));
        const [indirizzoGestore, bancaA, bancaB] = await chain.request({ method: 'eth_accounts' });
        const gestore = portafoglio(indirizzoGestore);
        const pubblicazione = await gestore.deployContract({ abi, bytecode: bytecode.object });
        const { contractAddress } = await chain.waitForTransactionReceipt({ hash: pubblicazione });
        const apri = async (conti, banca) => {
            const hash = await gestore.writeContract({ address: contractAddress, abi, functionName: 'apri', args: [conti, banca, SALDO_INIZIALE] });
            await chain.waitForTransactionReceipt({ hash });
        };
        await apri(CONTI_A, bancaA);
        await apri(CONTI_B, bancaB);
        await writeFile(FILE_INDIRIZZO, contractAddress);
    },

    async apri() {
        const [, bancaA, bancaB] = await chain.request({ method: 'eth_accounts' });
        this.abi = JSON.parse(await readFile(CONTRATTO, 'utf8')).abi;
        this.indirizzo = (await readFile(FILE_INDIRIZZO, 'utf8')).trim();
        this.banche = { A: portafoglio(bancaA), B: portafoglio(bancaB) };
        this.gas = [];
    },

    async bonifico(da, a, centesimi, { aMeta, chiFirma = bancaDi(da) } = {}) {
        const hash = await this.banche[chiFirma].writeContract({
            address: this.indirizzo, abi: this.abi, functionName: 'bonifico', args: [da, a, centesimi],
        });
        if (aMeta) {
            interrompi();
        }
        const ricevuta = await chain.waitForTransactionReceipt({ hash });
        if (ricevuta.status !== 'success') {
            throw new Error(`Bonifico ${da} -> ${a} fallito nel blocco ${ricevuta.blockNumber}`);
        }
        this.gas.push(Number(ricevuta.gasUsed));
    },

    async simula(da, a, centesimi, chiFirma) {
        await chain.simulateContract({
            account: this.banche[chiFirma].account, address: this.indirizzo, abi: this.abi, functionName: 'bonifico', args: [da, a, centesimi],
        });
    },

    async saldi() {
        const somma = async conti => {
            const saldi = await Promise.all(conti.map(conto => chain.readContract({
                address: this.indirizzo, abi: this.abi, functionName: 'saldo', args: [conto],
            })));
            return saldi.reduce((totale, saldo) => totale + Number(saldo), 0);
        };
        return { A: await somma(CONTI_A), B: await somma(CONTI_B) };
    },
};

export const registri = { uno: unDatabase, due: dueDatabase, chain: registroCondiviso };
