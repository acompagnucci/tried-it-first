import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import puppeteer from "puppeteer";

const qui = dirname(fileURLToPath(import.meta.url));
const prove = join(qui, "prove");
const require = createRequire(import.meta.url);
const axe = await readFile(require.resolve("axe-core/axe.min.js"), "utf8");

const pagine = process.argv.length > 2 ? process.argv.slice(2) : ["fatta-a-mano", "nativa", "solo-immagini"];
const regole = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];
const ignorati = new Set(["RootWebArea", "generic", "none", "main", "form", "banner"]);
const MASSIMO_TAB = 10;

function righe(nodo, out = []) {
  if (!nodo) return out;
  if (!ignorati.has(nodo.role)) {
    out.push(nodo.name ? `${nodo.role}: "${nodo.name}"` : `${nodo.role}: (senza nome)`);
  }
  for (const figlio of nodo.children ?? []) righe(figlio, out);
  return out;
}

function nomeAttivo() {
  const el = document.activeElement;
  if (!el || el === document.body) return null;
  return el.labels?.[0]?.textContent.trim() || el.textContent.trim() || el.tagName.toLowerCase();
}

async function giroDelTab(page) {
  const tab = [];
  for (let i = 0; i < MASSIMO_TAB; i++) {
    await page.keyboard.press("Tab");
    const attivo = await page.evaluate(nomeAttivo);
    if (!attivo || tab.includes(attivo)) break;
    tab.push(attivo);
  }
  return tab;
}

async function sceltaDaTastiera(page) {
  await page.evaluate(() => document.activeElement?.blur());
  await page.keyboard.press("Tab");
  const primo = await page.evaluate(nomeAttivo);
  if (primo !== "Paga dal conto") return `non raggiungibile: il primo Tab va su "${primo}"`;
  await page.keyboard.press("Space");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  const conto = await page.evaluate(() => document.forms[0].conto.value);
  return `Spazio, freccia giù, Invio: conto ${conto}`;
}

async function apriTendina(page) {
  await page.click(".conto");
  await page.waitForFunction(() => document.querySelector(".conto:open") || !document.getElementById("elenco")?.hidden);
}

await mkdir(prove, { recursive: true });
const browser = await puppeteer.launch();
const risultati = {};
try {
  for (const nome of pagine) {
    const page = await browser.newPage();
    await page.setViewport({ width: 420, height: 560, deviceScaleFactor: 2 });
    await page.goto(pathToFileURL(join(qui, nome, "index.html")).href);
    await page.screenshot({ path: join(prove, `${nome}-chiusa.png`) });

    await page.addScriptTag({ content: axe });
    const esito = await page.evaluate((tags) => axe.run(document, { runOnly: { type: "tag", values: tags } }), regole);
    const violazioni = esito.violations.map((v) => ({ regola: v.id, impatto: v.impact, descrizione: v.help, elementi: v.nodes.length }));

    const chiusa = righe(await page.accessibility.snapshot({ interestingOnly: true }));
    const tab = await giroDelTab(page);
    const tastiera = await sceltaDaTastiera(page);

    await page.reload();
    await apriTendina(page);
    const aperta = righe(await page.accessibility.snapshot({ interestingOnly: true }));
    await page.screenshot({ path: join(prove, `${nome}-aperta.png`) });

    risultati[nome] = { violazioni, chiusa, aperta, tab, tastiera };

    console.log(`\n=== ${nome} ===`);
    console.log(`Violazioni axe-core (WCAG 2.2 A/AA): ${violazioni.length} regole, ${violazioni.reduce((s, v) => s + v.elementi, 0)} elementi`);
    for (const v of violazioni) console.log(`  [${v.impatto}] ${v.regola}: ${v.descrizione} (${v.elementi})`);
    console.log("Albero di accessibilità, tendina chiusa:");
    for (const r of chiusa) console.log(`  ${r}`);
    console.log("Albero di accessibilità, tendina aperta:");
    for (const r of aperta) console.log(`  ${r}`);
    console.log(`Raggiungibili con Tab (${tab.length}): ${tab.join(" → ")}`);
    console.log(`Secondo conto solo con la tastiera: ${tastiera}`);
    await page.close();
  }
} finally {
  await browser.close();
}

await writeFile(join(prove, "misura.json"), JSON.stringify(risultati, null, 2) + "\n");
console.log("\nRisultati completi in prove/misura.json, screenshot in prove/");
