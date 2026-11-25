// Chaostest van de preview: dingen die een gewone gebruiker per ongeluk doet of die de telefoon doet.
// Kapotte of oude opslag, opslag die weigert, razendsnel tikken en vegen, herladen midden in een handeling,
// draaien en in- en uitvouwen, terug-knop, extreme invoer, begroeting op elke breedte. Faalt bij JavaScript-fouten, een leeg scherm,
// horizontaal scrollen, of meer dan één venster tegelijk.
//   node tests/chaos-preview.mjs
import { createRequire } from "node:module";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require(path.join(execSync("npm root -g").toString().trim(), "playwright"));
const URL = "file://" + path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../prototype/index.html");
const KEY = "vitalconnect.preview.v1";
const fouten = [];
const browser = await chromium.launch();

async function nieuw(opslag, opties = {}) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, ...opties });
  const p = await ctx.newPage();
  p.setDefaultTimeout(1500);
  p.on("pageerror", (e) => fouten.push(`${p._naam}: ${e.message}`));
  p.on("dialog", (d) => { fouten.push(`${p._naam}: onverwachte dialoog`); d.dismiss(); });
  await p.goto(URL);
  if (opslag !== undefined) { await p.evaluate(([k, v]) => localStorage.setItem(k, v), [KEY, opslag]); await p.reload(); }
  await p.waitForTimeout(250);
  return p;
}

async function controleer(p, naam) {
  const r = await p.evaluate(() => {
    const zichtbaar = [...document.querySelectorAll(".app>section")].filter((s) => !s.hidden);
    return {
      secties: zichtbaar.length,
      leeg: zichtbaar.some((s) => s.innerText.trim().length < 20),
      breed: document.documentElement.scrollWidth > innerWidth + 1,
      vensters: document.querySelectorAll("#disc").length,
      nan: /NaN|undefined|\[object Object\]/.test(document.body.innerText),
    };
  });
  if (r.secties !== 1) fouten.push(`${naam}: ${r.secties} schermen tegelijk zichtbaar`);
  if (r.leeg) fouten.push(`${naam}: leeg scherm`);
  if (r.breed) fouten.push(`${naam}: scrolt horizontaal`);
  if (r.vensters > 1) fouten.push(`${naam}: ${r.vensters} vensters tegelijk`);
  if (r.nan) fouten.push(`${naam}: NaN/undefined in beeld`);
}

const veeg = (p, dx) => p.evaluate((dx) => {
  const t = (x) => new Touch({ identifier: 1, target: document.body, clientX: x, clientY: 420 });
  const s = document.querySelector(".app>section:not([hidden])");
  s.dispatchEvent(new TouchEvent("touchstart", { touches: [t(200)], changedTouches: [t(200)], bubbles: true }));
  s.dispatchEvent(new TouchEvent("touchend", { touches: [], changedTouches: [t(200 + dx)], bubbles: true }));
}, dx);

// 1. Kapotte en oude opslag
for (const [naam, waarde] of [
  ["kapotte JSON", "{dit is geen json"],
  ["lege opslag", ""],
  ["verkeerd type", "[1,2,3]"],
  ["null-velden", JSON.stringify({ onb: null, order: null, rings: null, goal: null, lay: null, prof: null, conn: null })],
  ["oude versie", JSON.stringify({ order: ["steps", "bestaat_niet"], size: {}, goal: { steps: -5 }, rings: ["x", "y", "z", "q"], conn: {} })],
  ["rare waarden", JSON.stringify({ onb: null, sync: "vaak", theme: 42, lay: { sleepTiles: "hrv" }, prof: { name: 123, h: "abc" }, wsrc: "strava", wmin: 15, wlight: "ja" })],
  ["kapotte trainingen", JSON.stringify({ onb: null, wk: [null, 5, "x", { id: 1 }, { id: "a", sport: "raket", source: "manual", day: "2026-01-01", start: 1, dur: 600 },
    { id: "b", sport: "running", source: "manual", day: "gisteren", start: 1, dur: 600 }, { id: "c", sport: "running", source: "file", day: "2026-11-20", start: 1.7e12, dur: 1e9 },
    { id: "<img src=x onerror=alert(1)>", sport: "running", source: "manual", day: "2026-11-20", start: 1.7637e12, dur: 1800, title: "<b>x</b>", avg: "veel", dist: -5 }] })],
]) {
  const p = await nieuw(waarde); p._naam = naam;
  await controleer(p, naam);
  for (const tab of ["health", "sleep", "training", "today"]) { await p.click(`[data-tab=${tab}]`).catch(() => {}); }
  await controleer(p, naam + " na tabbladen");
  await p.context().close();
}

// 2. Opslag die weigert (privémodus, vol)
{
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  await ctx.addInitScript(() => { Storage.prototype.setItem = () => { throw new Error("QuotaExceededError"); }; });
  const p = await ctx.newPage(); p._naam = "opslag vol"; p.setDefaultTimeout(1500);
  p.on("pageerror", (e) => fouten.push(`opslag vol: ${e.message}`));
  await p.goto(URL); await p.waitForTimeout(250);
  await p.click("[data-act=google]"); await p.waitForTimeout(900);
  await p.check("#agree"); await p.click("[data-act=consent]");
  await controleer(p, "opslag vol");
  await ctx.close();
}

// 3. Razendsnel: 60x van tabblad wisselen, dubbel tikken, vegen tijdens animatie
{
  const p = await nieuw(JSON.stringify({ onb: null })); p._naam = "snel";
  for (let i = 0; i < 60; i++) await p.click(`[data-tab=${["today", "health", "sleep", "training"][i % 4]}]`, { delay: 0 });
  for (let i = 0; i < 40; i++) await veeg(p, i % 2 ? 150 : -150);
  await controleer(p, "snel wisselen en vegen");
  await p.click("[data-tab=today]"); await p.click("#syncBtn", { clickCount: 3 }); await p.waitForTimeout(1100);
  await p.click("[data-act=settings]"); await p.click("[data-ptab=conn]");
  await p.click("[data-conn=google]"); await p.click("[data-conn=google]").catch(() => {}); await p.click("[data-conn=hc]").catch(() => {});
  await controleer(p, "dubbel koppelen");
  await p.keyboard.press("Escape");
  await p.context().close();
}

// 4. Herladen midden in een handeling, draaien en in- en uitvouwen
{
  const p = await nieuw(undefined); p._naam = "herladen";
  await p.click("[data-act=google]"); await p.reload(); await p.waitForTimeout(900);
  await controleer(p, "herladen tijdens inloggen");
  await p.evaluate((k) => localStorage.setItem(k, JSON.stringify({ onb: null })), KEY); await p.reload();
  await p.click("[data-act=edit]"); await p.click("[data-radd=kcal]").catch(() => {}); await p.reload(); await p.waitForTimeout(300);
  await controleer(p, "herladen tijdens aanpassen");
  for (const [w, h] of [[844, 390], [300, 700], [673, 841], [1100, 800], [390, 844]]) {
    await p.setViewportSize({ width: w, height: h }); await p.waitForTimeout(120);
    await controleer(p, `formaat ${w}x${h}`);
  }
  await p.goBack().catch(() => {}); await p.goForward().catch(() => {});
  await p.context().close();
}

// 5. Extreme invoer in het profiel
{
  const p = await nieuw(JSON.stringify({ onb: null })); p._naam = "invoer";
  await p.click("[data-act=settings]");
  for (const [veld, waarde] of [["#p-name", "😀".repeat(80) + "<script>alert(1)</script>"], ["#p-h", "1e309"], ["#p-w", "-0"], ["#p-birth", "9999-99-99"]]) {
    await p.fill(veld, waarde).catch(() => {}); await p.dispatchEvent(veld, "change").catch(() => {});
  }
  await p.click("[data-act=psave]").catch(() => {});
  await p.click("[data-act=back]");
  await controleer(p, "extreme invoer");
  await p.context().close();
}

// 5b. Training: onzin in het formulier, rommel importeren, verwijderen
{
  const p = await nieuw(JSON.stringify({ onb: null })); p._naam = "training";
  await p.click("[data-tab=training]"); await p.click("[data-act=wnew]");
  for (const [veld, waarde] of [["#wtitle", "😀".repeat(100) + "<script>alert(1)</script>"], ["#wm", "1e309"], ["#wh", "-3"], ["#wkm", "abc"], ["#wavg", "999"], ["#wdate", "9999-12-31"]]) {
    await p.fill(veld, waarde).catch(() => {});
  }
  await p.click("[data-act=wsave]", { clickCount: 3 });
  const melding = await p.textContent("#wnote").catch(() => "");
  if (!melding) fouten.push("training: onzin in het formulier werd zonder melding opgeslagen");
  await p.fill("#wdate", "2026-11-20"); await p.fill("#wh", "1"); await p.fill("#wm", "0"); await p.fill("#wkm", "10,5"); await p.fill("#wavg", "150");
  await p.click("[data-act=wsave]"); await p.waitForTimeout(200);
  await controleer(p, "training opslaan");
  const rommel = [
    { name: "leeg.json", mimeType: "application/json", buffer: Buffer.from("") },
    { name: "kapot.json", mimeType: "application/json", buffer: Buffer.from("{[") },
    { name: "diep.json", mimeType: "application/json", buffer: Buffer.from("[".repeat(5000) + "]".repeat(5000)) },
    { name: "getallen.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify([{ start_date: "2026-11-20T07:00:00Z", elapsed_time: 1e40, distance: "NaN" }, { start_date: "x", elapsed_time: 600 }])) },
    { name: "kapot.fit", mimeType: "application/octet-stream", buffer: Buffer.concat([Buffer.from([14, 0x10, 0, 0, 0xff, 0xff, 0, 0, 46, 70, 73, 84, 0, 0]), Buffer.alloc(400, 0x41)]) },
    { name: "foto.jpg", mimeType: "image/jpeg", buffer: Buffer.alloc(2000, 0xff) },
  ];
  await p.setInputFiles("#wfile", rommel); await p.waitForTimeout(600);
  await controleer(p, "rommel importeren");
  await p.click("[data-act=back]").catch(() => {});
  await p.click("[data-tab=training]"); await p.click("[data-wk^=m-]").catch(() => {});
  await p.click("[data-act=wdel]").catch(() => {}); await p.click("[data-act=wdelok]").catch(() => {}); await p.waitForTimeout(200);
  if (await p.locator("[data-wk^=m-]").count()) fouten.push("training: zelf gelogde training niet verwijderd");
  await controleer(p, "training verwijderen");
  for (let i = 0; i < 30; i++) { await p.click("[data-wk]", { delay: 0 }).catch(() => {}); await p.click("[data-act=back]", { delay: 0 }).catch(() => {}); }
  // Snel wisselen tussen alle trainingsbronnen en de schakelaar
  await p.click("[data-act=wfilter]").catch(() => {});
  for (let i = 0; i < 24; i++) {
    await p.click(`[data-wsrc=${["auto", "google_health", "health_connect", "all"][i % 4]}]`, { delay: 0 }).catch(() => {});
    if (i % 5 === 0) await p.click("[data-ntf=wlight]", { delay: 0 }).catch(() => {});
    await p.click(`[data-wmin="${[0, 10, 20, 30][i % 4]}"]`, { delay: 0 }).catch(() => {});
  }
  await controleer(p, "trainingsbron snel wisselen");
  await p.click("[data-act=back]").catch(() => {});
  await controleer(p, "snel trainingen openen en sluiten");
  await p.context().close();
}

// 6. Begroeting bij elke breedte en naam: nooit afgekapt, nooit kleiner dan 60%
for (const naam of ["Sam", "Ian", "Maximiliaan-Alexander", "Wwwwwwwwwwwwwwwwwwwwwwwwwwwwww"]) for (const breed of [260, 320, 360, 412, 900]) {
  const p = await nieuw(JSON.stringify({ onb: null, prof: { name: naam } }), { viewport: { width: breed, height: 800 } });
  p._naam = `begroeting ${naam} ${breed}px`;
  const r = await p.evaluate(() => {
    const e = document.querySelector("#v-today .fit");
    if (!e) return { geen: true };
    const een = !e.classList.contains("two");
    return { afgekapt: een ? e.scrollWidth > e.clientWidth + 1 : e.scrollHeight > e.clientHeight + 1, px: parseFloat(e.style.fontSize) };
  });
  if (r.geen) fouten.push(`${p._naam}: geen begroeting`);
  else {
    if (r.afgekapt && !naam.startsWith("Wwww")) fouten.push(`${p._naam}: begroeting afgekapt`);
    if (!(r.px >= 17.9)) fouten.push(`${p._naam}: begroeting te klein (${r.px}px)`);
  }
  await controleer(p, p._naam);
  await p.context().close();
}

await browser.close();
const uniek = [...new Set(fouten)];
console.log(uniek.length ? `${uniek.length} problemen:\n- ` + uniek.join("\n- ") : "Geslaagd: geen fouten in de chaostest.");
process.exit(uniek.length ? 1 : 0);
