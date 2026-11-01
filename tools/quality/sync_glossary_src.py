#!/usr/bin/env python3
"""Propagates edits made directly to app/src/main/assets/glossary/*.json back into
tools/harvest/glossary_src/*.txt (the source build_glossary.js generates the JSON from), so a
later `node tools/harvest/build_glossary.js` does not revert them.

- existing `zh=English` lines get the JSON value,
- keys only in the JSON are appended under a "## Added from JSON" heading,
- keys removed from all JSON files are commented out.
Usage: python3 tools/quality/sync_glossary_src.py [--dry-run]
"""
import json, os, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
JSON_DIR = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'glossary')
SRC_DIR = os.path.join(ROOT, 'tools', 'harvest', 'glossary_src')
dry = '--dry-run' in sys.argv
norm = lambda s: ' '.join(s.split())

ALL = {}
for name in sorted(f for f in os.listdir(JSON_DIR) if f.endswith('.json')):
    ALL.update(json.load(open(os.path.join(JSON_DIR, name), encoding='utf-8')))

for name in sorted(f for f in os.listdir(JSON_DIR) if f.endswith('.json')):
    data = json.load(open(os.path.join(JSON_DIR, name), encoding='utf-8'))
    src_path = os.path.join(SRC_DIR, name[:-5] + '.txt')
    lines = open(src_path, encoding='utf-8').read().split('\n') if os.path.exists(src_path) else []
    seen, out, changed = set(), [], 0
    for line in lines:
        s = line.strip()
        if s and not s.startswith('#') and '=' in line:
            k = norm(line.split('=', 1)[0])
            if k in data:
                if k not in seen:
                    new = f'{k}={data[k]}'
                    changed += new != line
                    out.append(new)
                    seen.add(k)
                continue
            if k in ALL:  # defined in another file (build_glossary.js drops identical repeats)
                out.append(line)
                continue
            out.append('# removed: ' + line)
            changed += 1
            continue
        out.append(line)
    extra = [k for k in data if k not in seen]
    if extra:
        while out and out[-1] == '':
            out.pop()
        out += ['', '## Added from JSON'] + [f'{k}={data[k]}' for k in extra] + ['']
    print(f'{name}: {changed} changed/removed, {len(extra)} added')
    if not dry:
        open(src_path, 'w', encoding='utf-8').write('\n'.join(out))
