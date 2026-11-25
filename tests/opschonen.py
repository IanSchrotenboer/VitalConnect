#!/usr/bin/env python3
"""Opschoon- en veiligheidscontrole voor Vital Connect.

    python3 tests/opschonen.py               # zoekt overbodige code
    python3 tests/opschonen.py --veiligheid  # controleert app-instellingen en geheimen

Het script verwijdert zelf niets: het toont kandidaten. Controleer elke melding voordat je iets weghaalt,
zodat er geen functie van de app verdwijnt. Exitcode 1 als er iets gevonden is.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "android/app/src/main"
KT = sorted((APP / "java").rglob("*.kt"))
TS = sorted((ROOT / "supabase/functions").rglob("*.ts"))
PREVIEW = ROOT / "prototype/index.html"

meldingen: list[str] = []


def meld(soort: str, tekst: str) -> None:
    meldingen.append(f"[{soort}] {tekst}")


def zonder_commentaar(code: str) -> str:
    # Tekst tussen aanhalingstekens blijft staan, zodat bijvoorbeeld "*/*" of "https://" geen commentaar lijkt
    return re.sub(r'"(?:\\.|[^"\\\n])*"|/\*.*?\*/|//[^\n]*', lambda m: m.group(0) if m.group(0).startswith('"') else "", code, flags=re.S)


# ---------- Opschonen ----------
def kotlin() -> None:
    bronnen = {f: f.read_text() for f in KT}
    alles = "\n".join(zonder_commentaar(t) for t in bronnen.values())
    for f, tekst in bronnen.items():
        code = zonder_commentaar(tekst)
        romp = re.sub(r"^import .*$", "", code, flags=re.M)
        for imp in re.findall(r"^import ([\w.]+)$", code, flags=re.M):
            naam = imp.rsplit(".", 1)[1]
            if naam in {"getValue", "setValue"} and re.search(r"\b(var|val)\s+\w+(\s*:\s*[\w<>?, ]+)?\s+by\b", romp):
                continue  # gebruikt via "by" (gedelegeerde eigenschap)
            if not re.search(rf"\b{re.escape(naam)}\b", romp):
                meld("ongebruikte import", f"{f.relative_to(ROOT)}: {imp}")
    # Functies, klassen en waarden die nergens anders genoemd worden.
    for f, tekst in bronnen.items():
        for m in re.finditer(r"^\s*(?:private |internal )?(?:fun|val|var|class|object|enum class|data class)\s+(?:[\w.<>]+\.)?(\w+)", zonder_commentaar(tekst), flags=re.M):
            naam = m.group(1)
            if naam in {"MainActivity", "PermissionsRationaleActivity", "VitaalApplication", "SyncWorker"}:
                continue  # aangeroepen via het manifest of WorkManager
            if len(re.findall(rf"\b{re.escape(naam)}\b", alles)) == 1:
                meld("niet gebruikt", f"{f.relative_to(ROOT)}: {naam}")
    # Bronbestanden die nergens naar verwijzen
    res = APP / "res"
    verwijzingen = alles + "\n".join(p.read_text() for p in res.rglob("*.xml")) + (APP / "AndroidManifest.xml").read_text()
    for p in res.rglob("*"):
        if p.is_file() and p.parent.name.startswith(("drawable", "mipmap", "xml")) and p.stem not in {"ic_launcher", "ic_launcher_round"}:
            if not re.search(rf"[@.]\w+/{re.escape(p.stem)}\b|R\.\w+\.{re.escape(p.stem)}\b", verwijzingen):
                meld("ongebruikt bestand", str(p.relative_to(ROOT)))


def typescript() -> None:
    for f in TS:
        code = zonder_commentaar(f.read_text())
        for groep in re.findall(r"^import\s*\{([^}]*)\}\s*from", code, flags=re.M):
            for naam in [re.sub(r"^type\s+", "", n.strip()).split(" as ")[-1] for n in groep.split(",") if n.strip()]:
                if len(re.findall(rf"\b{re.escape(naam)}\b", code)) == 1:
                    meld("ongebruikte import", f"{f.relative_to(ROOT)}: {naam}")
    gedeeld = [f for f in TS if "_shared" in f.parts]
    rest = "\n".join(zonder_commentaar(f.read_text()) for f in TS)
    for f in gedeeld:
        for naam in re.findall(r"^export (?:async )?(?:function|const|class) (\w+)", f.read_text(), flags=re.M):
            if len(re.findall(rf"\b{re.escape(naam)}\b", rest)) == 1:
                meld("niet gebruikt", f"{f.relative_to(ROOT)}: {naam}")


def preview() -> None:
    html = PREVIEW.read_text()
    css = "\n".join(re.findall(r"<style>(.*?)</style>", html, flags=re.S))
    rest = html.replace(css, "")
    for klasse in sorted(set(re.findall(r"\.([a-z][\w-]*)", re.sub(r"\{[^}]*\}", "{}", css)))):
        if not re.search(rf"\b{re.escape(klasse)}\b", rest):
            meld("ongebruikte CSS", f"prototype/index.html: .{klasse}")
    js = "\n".join(re.findall(r"<script[^>]*>(.*?)</script>", html, flags=re.S))
    for naam in sorted(set(re.findall(r"(?:function\s+|const\s+|let\s+)(\w+)\s*[=(]", js))):
        if len(re.findall(rf"\b{re.escape(naam)}\b", js)) == 1:
            meld("niet gebruikt", f"prototype/index.html: {naam}")


def dubbel() -> None:
    """Gelijke blokken van 6 of meer betekenisvolle regels: kandidaat om samen te voegen."""
    blokken: dict[tuple, list[str]] = {}
    for f in KT + TS:
        regels = [(i + 1, r.strip()) for i, r in enumerate(zonder_commentaar(f.read_text()).splitlines())]
        regels = [(n, r) for n, r in regels if len(r) > 3 and not r.startswith(("import ", "}", ")"))]
        for i in range(len(regels) - 5):
            sleutel = tuple(r for _, r in regels[i:i + 6])
            blokken.setdefault(sleutel, []).append(f"{f.relative_to(ROOT)}:{regels[i][0]}")
    gemeld: set[str] = set()
    for plekken in blokken.values():
        if len(plekken) > 1 and not gemeld.intersection(plekken):
            meld("dubbele code", " en ".join(plekken))
            gemeld.update(plekken)


def uitgecommentarieerd() -> None:
    """Uitgeschakelde code (// val x = ...) hoort niet te blijven staan."""
    for f in KT + TS:
        for n, r in enumerate(f.read_text().splitlines(), 1):
            if re.match(r"\s*//\s*(val|var|fun|const|let|return|if \(|await|import)\b.*[=;({]\s*$", r):
                meld("uitgeschakelde code", f"{f.relative_to(ROOT)}:{n}")


def sql_functies() -> None:
    """Databasefuncties uit de migraties waar niets meer naar verwijst (app, edge functions, cron, andere SQL)."""
    migraties = sorted((ROOT / "supabase/migrations").glob("*.sql"))
    sql = "\n".join(re.sub(r"--[^\n]*", "", p.read_text()) for p in migraties)
    weg = set(re.findall(r"drop function (?:if exists )?public\.(\w+)", sql))
    namen = set(re.findall(r"create (?:or replace )?function public\.(\w+)", sql)) - weg
    elders = "\n".join(f.read_text() for f in KT + TS)
    for naam in sorted(namen):
        # verwijzingen die geen definitie, grant, revoke of comment zijn
        zonder = re.sub(rf"(?<!execute )(?:function|routine)\s+public\.{naam}\b", "", sql)
        if not re.search(rf"\b{naam}\b", zonder) and not re.search(rf"[\"']{naam}[\"']", elders):
            meld("niet gebruikt", f"databasefunctie public.{naam}")


def supabase_config() -> None:
    toml = (ROOT / "supabase/config.toml").read_text()
    for d in sorted((ROOT / "supabase/functions").iterdir()):
        if d.is_dir() and not d.name.startswith("_") and f"[functions.{d.name}]" not in toml:
            meld("ontbreekt", f"supabase/config.toml mist [functions.{d.name}]")


# ---------- Veiligheid ----------
def veiligheid() -> None:
    manifest = (APP / "AndroidManifest.xml").read_text()
    eisen = {
        'android:allowBackup="false"': "back-up van app-data moet uit staan",
        'android:dataExtractionRules=': "regels voor back-up en overzetten naar een nieuw toestel ontbreken",
        'android:usesCleartextTraffic="false"': "onversleuteld verkeer (http) moet uit staan",
    }
    for stuk, uitleg in eisen.items():
        if stuk not in manifest:
            meld("veiligheid", f"AndroidManifest.xml: {uitleg}")
    for m in re.finditer(r"<(activity|receiver|service|provider)[^>]*android:exported=\"true\"[^>]*>", manifest, flags=re.S):
        if "MainActivity" not in m.group(0) and "PermissionsRationale" not in m.group(0):
            meld("veiligheid", f"AndroidManifest.xml: onverwacht geëxporteerd onderdeel: {m.group(0)[:80]}")
    api = (APP / "java/nl/vitaal/app/Api.kt").read_text()
    if "SecureStore" not in api or re.search(r"putString\((ACCESS|REFRESH)", api):
        meld("veiligheid", "Api.kt: tokens moeten via SecureStore (Android Keystore) worden opgeslagen")
    for f in KT:
        code = f.read_text()
        if re.search(r"Log\.\w\(.*(token|password|wachtwoord|email)", code, flags=re.I):
            meld("veiligheid", f"{f.relative_to(ROOT)}: mogelijk gevoelige gegevens in het logboek")
        if re.search(r'"http://(?!schemas\.android)', code):
            meld("veiligheid", f"{f.relative_to(ROOT)}: http:// in plaats van https://")
    # Geheimen die nooit in de repo mogen staan
    bestanden = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split()
    patronen = {
        r"sb_secret_[A-Za-z0-9_-]{10,}": "Supabase secret key",
        r"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.eyJ[^\"']*service_role": "Supabase service_role-sleutel",
        r"-----BEGIN (RSA |EC )?PRIVATE KEY-----": "privésleutel",
        r"GOCSPX-[A-Za-z0-9_-]{10,}": "Google client secret",
        r"re_[A-Za-z0-9]{8,}_[A-Za-z0-9]{8,}": "Resend API-sleutel",
    }
    for naam in bestanden:
        p = ROOT / naam
        if p.suffix in {".keystore", ".jks", ".p12", ".pem"} or p.name in {".env", "keystore-base64.txt"}:
            meld("veiligheid", f"{naam}: sleutelbestand hoort niet in de repo")
            continue
        if p.suffix in {".png", ".jpg", ".webp", ".jar"} or not p.is_file():
            continue
        tekst = p.read_text(errors="ignore")
        for patroon, wat in patronen.items():
            if re.search(patroon, tekst):
                meld("veiligheid", f"{naam}: bevat een {wat}")


if __name__ == "__main__":
    if "--veiligheid" in sys.argv:
        veiligheid()
    else:
        kotlin()
        typescript()
        preview()
        dubbel()
        uitgecommentarieerd()
        sql_functies()
        supabase_config()
    print("\n".join(meldingen) if meldingen else "Niets gevonden.")
    sys.exit(1 if meldingen else 0)
