import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import puppeteer from "puppeteer";

const qui = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const axe = await readFile(require.resolve("axe-core/axe.min.js"), "utf8");

const pagine = process.argv.slice(2).length ? process.argv.slice(2) : ["prima", "dopo"];
const regole = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

function righe(nodo, out = []) {
  if (!nodo) return out;
  if (nodo.role !== "RootWebArea" && nodo.role !== "generic" && nodo.role !== "none") {
    out.push(nodo.name ? `${nodo.role}: "${nodo.name}"` : `${nodo.role}: (senza nome)`);
  }
  for (const figlio of nodo.children ?? []) righe(figlio, out);
  return out;
}

const browser = await puppeteer.launch();
const risultati = {};
try {
  for (const nome of pagine) {
    const page = await browser.newPage();
    await page.setViewport({ width: 420, height: 900 });
    await page.goto(pathToFileURL(join(qui, nome, "index.html")).href);

    await page.addScriptTag({ content: axe });
    const esito = await page.evaluate((tags) => axe.run(document, { runOnly: { type: "tag", values: tags } }), regole);
    const violazioni = esito.violations.map((v) => ({ regola: v.id, impatto: v.impact, descrizione: v.help, elementi: v.nodes.length }));

    const albero = righe(await page.accessibility.snapshot({ interestingOnly: true }));

    const tab = [];
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press("Tab");
      const attivo = await page.evaluate(() => {
        const el = document.activeElement;
        if (!el || el === document.body) return null;
        return el.getAttribute("aria-label") || el.labels?.[0]?.textContent?.trim() || el.placeholder || el.textContent.trim() || el.tagName.toLowerCase();
      });
      if (!attivo || tab.includes(attivo)) break;
      tab.push(attivo);
    }

    risultati[nome] = { violazioni, albero, tab };

    console.log(`\n=== ${nome} ===`);
    console.log(`Violazioni axe-core (WCAG 2.2 A/AA): ${violazioni.length} regole, ${violazioni.reduce((s, v) => s + v.elementi, 0)} elementi`);
    for (const v of violazioni) console.log(`  [${v.impatto}] ${v.regola}: ${v.descrizione} (${v.elementi})`);
    console.log("Cosa sente lo screen reader:");
    for (const r of albero) console.log(`  ${r}`);
    console.log(`Raggiungibili con Tab (${tab.length}): ${tab.join(" → ")}`);
    await page.close();
  }
} finally {
  await browser.close();
}

await mkdir(join(qui, "prove"), { recursive: true });
await writeFile(join(qui, "prove", "misura.json"), JSON.stringify(risultati, null, 2) + "\n");
console.log("\nRisultati completi in prove/misura.json");
