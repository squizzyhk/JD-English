#!/usr/bin/env node
// Builds app/src/main/assets/glossary/*.json from tools/harvest/glossary_src/*.txt.
// Source format: one `中文=English` per line; `#` starts a comment line.
// Within a file the first definition wins (conflicts are reported). Keys already defined
// identically in an earlier file are dropped from later files; differing values are reported
// (the app loader merges files alphabetically and the later file wins).
// Usage: node tools/harvest/build_glossary.js [--strict]
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const SRC = path.join(__dirname, 'glossary_src');
const OUT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'glossary');
const strict = process.argv.includes('--strict');

const norm = (s) => s.trim().replace(/\s+/g, ' ');
const files = fs.readdirSync(SRC).filter((f) => f.endsWith('.txt')).sort();
fs.mkdirSync(OUT, { recursive: true });

const global = new Map(); // key -> {en, file}
let problems = 0;
let total = 0;
for (const f of files) {
  const lines = fs.readFileSync(path.join(SRC, f), 'utf8').split(/\r?\n/);
  const out = new Map();
  lines.forEach((line, i) => {
    if (!line.trim() || line.trimStart().startsWith('#')) return;
    const eq = line.indexOf('=');
    if (eq <= 0) { console.error(`${f}:${i + 1}: no '=': ${line}`); problems++; return; }
    const zh = norm(line.slice(0, eq));
    const en = line.slice(eq + 1).trim();
    if (!zh || !en) { console.error(`${f}:${i + 1}: empty side: ${line}`); problems++; return; }
    if (!/[㐀-鿿]/.test(zh)) { console.error(`${f}:${i + 1}: key has no Han: ${zh}`); problems++; return; }
    if (/[㐀-鿿]/.test(en)) { console.error(`${f}:${i + 1}: value contains Han: ${en}`); problems++; return; }
    if (out.has(zh)) {
      if (out.get(zh) !== en) console.error(`${f}:${i + 1}: in-file conflict for ${zh}: "${out.get(zh)}" vs "${en}" (keeping first)`);
      return;
    }
    const prev = global.get(zh);
    if (prev) {
      if (prev.en === en) return; // identical, already defined earlier
      console.error(`${f}:${i + 1}: overrides ${prev.file}: ${zh}: "${prev.en}" -> "${en}"`);
    }
    out.set(zh, en);
  });
  for (const [zh, en] of out) global.set(zh, { en, file: f });
  const obj = {};
  for (const [zh, en] of out) obj[zh] = en;
  const json = '{\n' + Object.entries(obj).map(([k, v]) => '  ' + JSON.stringify(k) + ': ' + JSON.stringify(v)).join(',\n') + '\n}\n';
  fs.writeFileSync(path.join(OUT, f.replace(/\.txt$/, '.json')), json);
  console.log(`${f.replace(/\.txt$/, '.json')}: ${out.size} entries`);
  total += out.size;
}
console.log(`total: ${total} entries, ${global.size} distinct keys`);
if (problems && strict) process.exit(1);
