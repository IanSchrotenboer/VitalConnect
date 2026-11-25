// Functionele test van de preview: elke hoofdhandeling geeft de juiste uitkomst (niet alleen "geen fout").
// Draai: node tests/functioneel-preview.mjs
// Controleert Vandaag (ringen, percentages, motivatie), aanpassen (ringen, tegels, herstel), detail (periodes),
// profiel (naam, thema), training (loggen, bewerken, verwijderen, importeren, filters), slaap, gezondheid,
// opslag na herladen en uitloggen. Elke mislukte verwachting komt in de lijst; aan het eind: geslaagd of de lijst.
import { createRequire } from "node:module";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(execSync("npm root -g").toString().trim() + "/playwright");
const URL = "file://" + path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../prototype/index.html");
const KEY = "vitalconnect.preview.v1";
const fouten = [];
const verwacht = (ok, wat) => { if (!ok) fouten.push(wat); };

const browser = await chromium.launch();
async function start(opslag, opties = {}) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 900 }, ...opties });
  const p = await ctx.newPage();
  p.setDefaultTimeout(3000);
  p.on("pageerror", (e) => fouten.push(`JavaScript-fout: ${e.message}`));
  p.on("dialog", (d) => { fouten.push(`onverwachte dialoog: ${d.message()}`); d.dismiss(); });
  await p.goto(URL);
  await p.evaluate(([k, v]) => localStorage.setItem(k, v), [KEY, JSON.stringify(opslag)]);
  await p.reload();
  await p.evaluate(() => document.getElementById("splash")?.remove());
  await p.waitForTimeout(400);
  return p;
}
const zichtbaar = (p, sel) => p.locator(sel).first().isVisible().catch(() => false);
const terug = async (p) => { await p.locator("[data-act=back]:visible").first().click().catch(() => {}); await p.waitForTimeout(100); };
const naarKoppelingen = async (p) => { await p.click("[data-tab=today]"); await p.click("[data-act=settings]"); await p.click("[data-ptab=conn]"); };

// ---------- Vandaag ----------
{
  const p = await start({ onb: null, prof: { name: "Ian" } });
  const r = await p.evaluate(() => {
    const rings = __vc.state().rings.filter((id) => __vc.goal(id) && !__vc.MI[id].lo).slice(0, 3);
    const fr = rings.map((id) => __vc.today(id) / __vc.goal(id));
    const pcts = [...document.querySelectorAll("#v-today .leg .pc")].map((e) => e.textContent);
    const open = rings.map((id, i) => [id, fr[i]]).filter((x) => x[1] < 1).sort((a, b) => b[1] - a[1])[0];
    return {
      n: rings.length, cirkels: document.querySelectorAll("#v-today .rp").length, pcts,
      verwachtPcts: fr.map((f) => Math.round(f * 100) + "%"),
      motivatie: document.querySelector("#v-today .mot span")?.textContent ?? "",
      open: open ? __vc.MI[open[0]].n.toLowerCase() : null,
      groet: document.querySelector("#v-today h1")?.textContent ?? "",
      tegelKleur: getComputedStyle(document.querySelector("#v-today .dtile")).backgroundColor,
      inTegels: rings.some((id) => document.querySelector(`#v-today [data-open="${id}"]`)),
    };
  });
  verwacht(r.n === 3 && r.cirkels === 3, `Vandaag: 3 ringen verwacht, ${r.cirkels} getekend`);
  verwacht(JSON.stringify(r.pcts) === JSON.stringify(r.verwachtPcts), `Vandaag: percentages ${r.pcts} ≠ ${r.verwachtPcts}`);
  verwacht(r.open ? r.motivatie.includes(r.open) : r.motivatie.includes("gehaald"), `Vandaag: motivatie "${r.motivatie}" noemt niet ${r.open}`);
  verwacht(r.groet.includes("Ian"), `Vandaag: begroeting zonder naam ("${r.groet}")`);
  verwacht(r.tegelKleur === "rgb(255, 255, 255)", `Vandaag licht: ringtegel niet wit (${r.tegelKleur})`);
  verwacht(!r.inTegels, "Vandaag: een metric uit de ringen staat ook als tegel");

  // Vernieuwen: bezig, daarna bijgewerkt
  await p.click("#syncBtn");
  const bezig = await p.textContent("#v-today .upd");
  verwacht(/Bijwerken/.test(bezig), `Vernieuwen: geen "Bijwerken…" (${bezig})`);
  await p.waitForTimeout(1800);
  verwacht(/Bijgewerkt om/.test(await p.textContent("#v-today .upd")), "Vernieuwen: daarna geen \"Bijgewerkt om\"");

  // Detail met periodes
  const eerste = await p.getAttribute("#v-today [data-open]:not([data-open=herstel])", "data-open");
  await p.click(`#v-today [data-open="${eerste}"]`);
  verwacht(await zichtbaar(p, "#v-detail"), "Detail: scherm opent niet");
  for (const d of [7, 30, 90, 365]) {
    await p.click(`[data-per="${d}"]`);
    const ok = await p.evaluate((d) => __vc.period() === d && document.querySelector(`[data-per="${d}"]`).getAttribute("aria-pressed") === "true", d);
    verwacht(ok, `Detail: periode ${d} wordt niet gekozen`);
  }
  verwacht(await p.locator("#v-detail details.uk").count() > 0, "Detail: uitleg over je lichaam ontbreekt");
  await terug(p);
  verwacht(await zichtbaar(p, "#v-today"), "Detail: terug gaat niet naar Vandaag");

  // Aanpassen: ring weg en terug, herstel uit
  await p.click("[data-act=edit]");
  const weg = await p.getAttribute("[data-rdel]", "data-rdel");
  await p.click(`[data-rdel="${weg}"]`);
  verwacht(await p.evaluate((id) => !__vc.state().rings.includes(id), weg), "Aanpassen: ring weghalen werkt niet");
  await p.click(`[data-radd="${weg}"]`);
  verwacht(await p.evaluate((id) => __vc.state().rings.includes(id), weg), "Aanpassen: ring toevoegen werkt niet");
  await p.uncheck("[data-lay=herstel]");
  await p.click("[data-act=done]");
  verwacht(!(await p.evaluate(() => !!document.querySelector("#v-today .herst"))), "Aanpassen: herstel uitzetten haalt de tegel niet weg");
  await p.click("[data-act=edit]"); await p.check("[data-lay=herstel]"); await p.click("[data-act=done]");

  // Profiel: naam en thema
  await p.click("[data-act=settings]");
  await p.fill("#p-name", "Fleur"); await p.dispatchEvent("#p-name", "change");
  await p.click("[data-act=psave]").catch(() => {});
  await terug(p);
  verwacht((await p.textContent("#v-today h1")).includes("Fleur"), "Profiel: nieuwe naam niet in de begroeting");
  await p.click("[data-act=settings]");
  await p.click('[data-theme-set="dark"]');
  const donker = await p.evaluate(() => document.documentElement.dataset.theme);
  await terug(p);
  const tegelDonker = await p.evaluate(() => getComputedStyle(document.querySelector("#v-today .dtile")).backgroundColor);
  verwacht(donker === "dark" && tegelDonker === "rgb(29, 35, 33)", `Thema donker: ${donker}, tegel ${tegelDonker}`);

  // Opslag blijft na herladen
  await p.reload(); await p.evaluate(() => document.getElementById("splash")?.remove()); await p.waitForTimeout(300);
  const na = await p.evaluate(() => ({ naam: __vc.state().prof.name, thema: __vc.state().theme }));
  verwacht(na.naam === "Fleur" && na.thema === "dark", `Herladen: naam/thema niet bewaard (${JSON.stringify(na)})`);

  // Slaap en Gezondheid
  await p.click("[data-tab=sleep]");
  verwacht(/Slaap/.test(await p.textContent("#v-sleep")), "Slaap: tabblad is leeg");
  await p.click("[data-tab=health]");
  verwacht((await p.locator("#v-health [data-open]").count()) > 3, "Gezondheid: te weinig statistieken");

  // Uitloggen
  await p.click("[data-tab=today]"); await p.click("[data-act=settings]");
  await p.click("[data-act=logout]");
  verwacht(await zichtbaar(p, "#v-auth"), "Uitloggen: inlogscherm verschijnt niet");
  await p.context().close();
}

// ---------- Training ----------
{
  const p = await start({ onb: null, prof: { birth: "1991-04-18", sex: "male" } });
  await p.click("[data-tab=training]");
  const voor = await p.evaluate(() => __vc.visibleWk().list.length);

  // Loggen
  await p.click("[data-act=wnew]");
  await p.click("[data-wsport=cycling]");
  await p.fill("#wtitle", "Testrit"); await p.fill("#wh", "1"); await p.fill("#wm", "5"); await p.fill("#wkm", "32,5"); await p.fill("#wavg", "140");
  await p.click("[data-act=wsave]");
  const gelogd = await p.evaluate(() => __vc.state().wk.find((w) => w.title === "Testrit"));
  verwacht(gelogd && gelogd.sport === "cycling" && gelogd.dur === 3900 && Math.round(gelogd.dist) === 32500 && gelogd.avg === 140,
    `Loggen: verkeerde waarden ${JSON.stringify(gelogd)}`);
  verwacht((await p.textContent("#v-training")).includes("Testrit"), "Loggen: training staat niet in de lijst");
  verwacht(await p.evaluate(() => __vc.visibleWk().list.length) === voor + 1, "Loggen: aantal trainingen klopt niet");

  // Bewerken
  await p.click(`[data-wk="${gelogd.id}"]`);
  await p.click("[data-act=wedit]");
  await p.fill("#wm", "20"); await p.click("[data-act=wsave]");
  verwacht(await p.evaluate((id) => __vc.state().wk.find((w) => w.id === id)?.dur, gelogd.id) === 4800, "Bewerken: duur niet aangepast");
  verwacht(await p.evaluate(() => __vc.state().wk.filter((w) => w.title === "Testrit").length) === 1, "Bewerken: training dubbel opgeslagen");

  // Verwijderen (met bevestiging in het scherm)
  await p.click(`[data-wk="${gelogd.id}"]`);
  await p.click("[data-act=wdel]"); await p.click("[data-act=wdelok]");
  verwacht(await p.evaluate((id) => !__vc.state().wk.some((w) => w.id === id), gelogd.id), "Verwijderen: training staat er nog");

  // Importeren: twee Strava-trainingen met dezelfde naam blijven twee trainingen
  const strava = JSON.stringify([
    { id: 101, name: "Ochtendloop", sport_type: "Run", start_date: "2026-11-20T06:30:00Z", elapsed_time: 2700, distance: 8000 },
    { id: 102, name: "Ochtendloop", sport_type: "Run", start_date: "2026-11-21T06:30:00Z", elapsed_time: 2400, distance: 7000 },
  ]);
  await p.click("[data-act=wimp]").catch(() => {});
  await p.setInputFiles("#wfile", [{ name: "strava.json", mimeType: "application/json", buffer: Buffer.from(strava) }]);
  await p.waitForTimeout(400);
  verwacht(/2 trainingen gevonden/.test(await p.textContent("#v-detail")), "Importeren: niet 2 trainingen gevonden");
  await p.click("[data-act=wsaveimp]");
  verwacht(await p.evaluate(() => __vc.state().wk.filter((w) => w.title === "Ochtendloop").length) === 2, "Importeren: niet beide trainingen opgeslagen");
  // Nog een keer importeren: bijwerken, niet verdubbelen
  await p.click("[data-act=wimp]").catch(() => {});
  await p.setInputFiles("#wfile", [{ name: "strava.json", mimeType: "application/json", buffer: Buffer.from(strava) }]);
  await p.waitForTimeout(300); await p.click("[data-act=wsaveimp]");
  verwacht(await p.evaluate(() => __vc.state().wk.filter((w) => w.title === "Ochtendloop").length) === 2, "Importeren: opnieuw importeren verdubbelt");

  // Filters
  await naarKoppelingen(p);
  await p.click('[data-wmin="20"]');
  verwacht(await p.evaluate(() => __vc.visibleWk().list.every((w) => w.sport !== "walking" || w.dur >= 1200 || w.source === "manual" || w.source === "file")),
    "Filter 20 min: kortere wandelingen tellen nog mee");
  await p.check("[data-ntf=wlight]");
  verwacht(await p.evaluate(() => { const lim = __vc.hrLimit(); return __vc.visibleWk().list.every((w) => w.sport !== "walking" || w.source === "manual" || w.source === "file" ||
    (w.avg != null && lim != null ? w.avg >= lim : w.dist == null || w.dist / w.dur * 3.6 >= 4)); }), "Filter licht: lichte wandelingen tellen nog mee");
  for (const [k, ok] of [["health_connect", (w) => w.source !== "google_health"], ["google_health", (w) => w.source !== "health_connect" && w.origin !== "HEALTH_CONNECT"]]) {
    await p.click(`[data-wsrc="${k}"]`);
    verwacht(await p.evaluate((src) => __vc.visibleWk().list.every((w) => w.source === "manual" || w.source === "file" ||
      (src === "health_connect" ? w.source === "health_connect" : w.source === "google_health" && w.origin !== "HEALTH_CONNECT")), k), `Bron ${k}: verkeerde trainingen`);
  }
  await p.click('[data-wsrc="auto"]');
  verwacht(await p.evaluate(() => __vc.state().wsrc === "auto" && __vc.state().wmin === 20 && __vc.state().wlight === true), "Filters: keuzes niet bewaard");
  await p.context().close();
}

await browser.close();
if (fouten.length) { console.log("Fouten in de functionele test:\n- " + fouten.join("\n- ")); process.exit(1); }
console.log("Geslaagd: alle functies geven de verwachte uitkomst.");
