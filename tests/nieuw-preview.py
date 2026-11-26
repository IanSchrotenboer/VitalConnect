#!/usr/bin/env python3
"""Zet de punten onder "## Nieuw" van de huidige versie in de preview (prototype/index.html), zoals de app ze toont.

Gebruik: python3 tests/nieuw-preview.py        (schrijft de preview bij)
         python3 tests/nieuw-preview.py --check (meldt alleen of de preview klopt; exit 1 als niet)
De regels zijn gelijk aan parseNew in android/app/src/main/java/nl/vitaal/app/WhatsNew.kt.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def versie():
    s = open(os.path.join(ROOT, 'android/app/build.gradle.kts'), encoding='utf-8').read()
    return re.search(r'versionName = "([^"]+)"', s).group(1)

def punten(md):
    lines = md.replace('\r', '').split('\n')
    try:
        start = next(i for i, l in enumerate(lines) if l.strip().lower() == '## nieuw')
    except StopIteration:
        return []
    items = []
    for line in lines[start + 1:]:
        if line.startswith('## '):
            break
        m = re.match(r'^- \*\*(.+?)\*\*\s*(.*)$', line)
        if m:
            items.append([m.group(1).strip().rstrip('.:').strip(), m.group(2).strip().lstrip(':').strip()])
        elif items and line.strip() and line.startswith(' '):
            t = line.strip()
            t = t[2:] if t.startswith(('- ', '* ')) else t
            items[-1][1] = (items[-1][1] + ' ' + t.strip()).strip()
    uit = []
    for t, x in items:
        x = re.sub(r'\s+', ' ', x.replace('**', '').replace('`', '')).strip()
        if t:
            uit.append([t.replace('`', ''), x[:1].upper() + x[1:]])
    return uit

def main():
    v = versie()
    f = os.path.join(ROOT, 'release-notities', f'v{v}.md')
    items = punten(open(f, encoding='utf-8').read()) if os.path.exists(f) else []
    blok = '/*NIEUW*/const VERSIE=%s,NIEUW=%s;/*/NIEUW*/' % (json.dumps(v), json.dumps(items, ensure_ascii=False))
    p = os.path.join(ROOT, 'prototype/index.html')
    s = open(p, encoding='utf-8').read()
    nieuw = re.sub(r'/\*NIEUW\*/.*?/\*/NIEUW\*/', lambda _: blok, s, count=1, flags=re.S)
    if '--check' in sys.argv:
        ok = nieuw == s
        print('Preview klopt met de notities van %s (%d %s).' % (v, len(items), 'punt' if len(items) == 1 else 'punten') if ok else 'Preview loopt achter: draai python3 tests/nieuw-preview.py')
        sys.exit(0 if ok else 1)
    open(p, 'w', encoding='utf-8').write(nieuw)
    print('Versie %s, %d %s bij Nieuw.' % (v, len(items), 'punt' if len(items) == 1 else 'punten'))

main()
