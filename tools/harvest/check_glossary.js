#!/usr/bin/env node
// Validates app/src/main/assets/glossary: every *.json parses, is a flat {string: string}
// object, has no duplicate keys (checked on the raw text, since JSON.parse silently keeps the
// last one), values contain no Han, and every _protected.txt term has a glossary translation.
// Usage: node tools/harvest/check_glossary.js   (exit code 1 on any error)
'use strict';
const fs = require('fs');
const path = require('path');

const DIR = path.resolve(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'glossary');
let errors = 0;
const err = (m) => { console.error('ERROR ' + m); errors++; };
const all = new Map();

// Minimal tokenizer that walks the top-level object and records every key in order.
function rawKeys(text) {
  const keys = [];
  let i = 0;
  const ws = () => { while (/\s/.test(text[i])) i++; };
  const str = () => {
    const start = i; i++;
    while (text[i] !== '"') { if (text[i] === '\\') i++; i++; if (i >= text.length) throw new Error('unterminated string'); }
    i++;
    return JSON.parse(text.slice(start, i));
  };
  ws(); if (text[i] !== '{') throw new Error('not an object'); i++; ws();
  if (text[i] === '}') return keys;
  for (;;) {
    ws(); keys.push(str()); ws();
    if (text[i] !== ':') throw new Error('expected :'); i++; ws();
    if (text[i] !== '"') throw new Error('value is not a string for key ' + keys[keys.length - 1]);
    str(); ws();
    if (text[i] === ',') { i++; continue; }
    if (text[i] === '}') break;
    throw new Error('expected , or }');
  }
  return keys;
}

const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.json')).sort();
if (!files.length) err('no glossary files');
for (const f of files) {
  const text = fs.readFileSync(path.join(DIR, f), 'utf8');
  let obj;
  try { obj = JSON.parse(text); } catch (e) { err(`${f}: invalid JSON: ${e.message}`); continue; }
  if (typeof obj !== 'object' || Array.isArray(obj) || obj === null) { err(`${f}: not a flat object`); continue; }
  let keys;
  try { keys = rawKeys(text); } catch (e) { err(`${f}: ${e.message}`); continue; }
  const seen = new Set();
  for (const k of keys) {
    if (seen.has(k)) err(`${f}: duplicate key ${k}`);
    seen.add(k);
  }
  for (const [k, v] of Object.entries(obj)) {
    if (typeof v !== 'string' || !v.trim()) err(`${f}: empty/non-string value for ${k}`);
    else if (/[㐀-鿿]/.test(v)) err(`${f}: value has Han for ${k}: ${v}`);
    if (k !== k.trim()) err(`${f}: key has surrounding whitespace: "${k}"`);
    all.set(k.trim().replace(/\s+/g, ' '), v);
  }
  console.log(`${f}: ${keys.length} keys OK`);
}

const prot = path.join(DIR, '_protected.txt');
if (fs.existsSync(prot)) {
  const terms = fs.readFileSync(prot, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter((s) => s && !s.startsWith('#'));
  const seen = new Set();
  for (const t of terms) {
    if (seen.has(t)) err(`_protected.txt: duplicate ${t}`);
    seen.add(t);
    if (!all.has(t)) err(`_protected.txt: no glossary translation for ${t}`);
  }
  console.log(`_protected.txt: ${terms.length} terms OK`);
} else err('missing _protected.txt');

console.log(`total distinct keys: ${all.size}`);
if (errors) { console.error(`${errors} error(s)`); process.exit(1); }
console.log('glossary OK');
