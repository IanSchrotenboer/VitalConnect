// Stresstest van de preview (prototype/index.html): een "apenproef" die duizenden willekeurige tikken en
// invoer doet op vier schermbreedtes (300 tot 1100 px), in licht en donker. Faalt bij JavaScript-fouten, bij een pagina die
// horizontaal gaat scrollen of bij knoppen kleiner dan 44 px.
//   node tests/stress-preview.mjs [acties per ronde, standaard 1500]
import { createRequire } from "node:module";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(path.join(execSync("npm root -g").toString().trim(), "playwright"));
const page_url = "file://" + path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../prototype/index.html");
const ACTIES = Number(process.argv[2] ?? 1200);
const RAAR = ["", "0", "-5", "999999", "abc", "1e309", "12,5", "<img src=x onerror=alert(1)>", "💥".repeat(40), " ", "NaN"];

let seed = 20261124;
const rnd = (n) => ((seed = (seed * 16807) % 2147483647) % n);

const browser = await chromium.launch();
const fouten = [];
let totaal = 0;

// Buitenscherm klaptelefoon, gewone telefoon, opengevouwen foldable staand en liggend
for (const [breedte, schema] of [[300, "light"], [400, "dark"], [673, "light"], [1100, "dark"]]) {
  const ctx = await browser.newContext({ viewport: { width: breedte, height: 800 }, colorScheme: schema });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => fouten.push(`${breedte}px ${schema}: ${e.message}`));
  page.on("dialog", (d) => { fouten.push(`${breedte}px: onverwachte dialoog (${d.message()})`); d.dismiss(); });
  await page.goto(page_url);
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await page.waitForTimeout(300);
  const t0 = Date.now();

  for (let i = 0; i < ACTIES; i++) {
    const doelen = await page.$$("button:visible, input:visible, a[href^='#']:visible");
    if (!doelen.length) { fouten.push(`${breedte}px: geen bedienbare elementen meer na ${i} acties`); break; }
    const el = doelen[rnd(doelen.length)];
    const soort = await el.evaluate((e) => e.tagName + ":" + (e.type || ""));
    try {
      if (soort === "INPUT:checkbox") await el.click({ timeout: 500 });
      else if (soort.startsWith("INPUT")) {
        await el.fill(soort === "INPUT:date" ? ["1990-02-30", "2999-01-01", "1899-12-31", ""][rnd(4)] : RAAR[rnd(RAAR.length)], { timeout: 500 }).catch(() => {});
        await el.dispatchEvent("change");
      } else await el.click({ timeout: 500 });
    } catch { /* element verdween door een vorige actie: geen fout */ }
    totaal++;

    if (i % 100 === 0) {
      const check = await page.evaluate(() => {
        const breed = document.documentElement.scrollWidth > window.innerWidth + 1;
        const klein = [...document.querySelectorAll("button, input:not([type=checkbox])")]
          .filter((e) => e.offsetParent && !e.closest(".tabs") && e.getBoundingClientRect().height < 44 && !e.classList.contains("lnk"))
          .map((e) => (e.textContent || e.id || e.className).trim().slice(0, 30));
        const m = document.body.innerText.match(/.{0,40}(NaN|undefined|Infinity).{0,20}/);
        return { breed, klein, nan: m && m[0].replace(/\n/g, " ") };
      });
      if (check.breed) fouten.push(`${breedte}px: pagina scrolt horizontaal (actie ${i})`);
      if (check.klein.length) fouten.push(`${breedte}px: tikdoel kleiner dan 44 px: ${[...new Set(check.klein)].join(", ")}`);
      if (check.nan) fouten.push(`${breedte}px: "${check.nan}" in beeld`);
    }
  }
  console.log(`${breedte}px ${schema}: ${ACTIES} acties in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  await ctx.close();
}
await browser.close();

const uniek = [...new Set(fouten)];
console.log(uniek.length ? `\n${uniek.length} problemen:\n- ` + uniek.slice(0, 40).join("\n- ") : `\nGeslaagd: ${totaal} acties zonder fouten.`);
process.exit(uniek.length ? 1 : 0);
