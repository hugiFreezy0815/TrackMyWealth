/**
 * US-384: independent production Expo web bundles, same app/shared dependencies.
 * Synthetic spike branch ONLY. This script modifies its working tree temporarily,
 * never commits the generated renderer-only route, and always restores it.
 *
 * Run: cd mobile && node scripts/spike-384-bundle-isolation.mjs
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';
import ts from 'typescript';

const mobile = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const routePath = path.join(mobile, 'src/app/spike-web-transactions.tsx');
const outDir = path.join(mobile, 'spike-reports');
const distDir = path.join(mobile, 'dist');
const original = fs.readFileSync(routePath, 'utf8');
const digest = (data) => crypto.createHash('sha256').update(data).digest('hex');

fs.mkdirSync(outDir, { recursive: true });

function isolatedRoute(mode) {
  const source = ts.createSourceFile(routePath, original, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  assert.equal(source.parseDiagnostics.length, 0, 'Baseline route does not parse as TSX');

  const omitName = mode === 'rnw' ? 'DomTable' : 'NativeWebList';
  const declaration = source.statements.find(
    (statement) => ts.isFunctionDeclaration(statement) && statement.name?.text === omitName
  );
  assert(declaration, 'Missing renderer declaration ' + omitName);

  const matches = [];
  function visit(node) {
    if (ts.isConditionalExpression(node)
      && node.condition.getText(source).replace(/\s/g, '') === "mode==='rnw'"
      && node.getText(source).includes('<NativeWebList')
      && node.getText(source).includes('<DomTable')) {
      matches.push(node);
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.equal(matches.length, 1, 'Expected exactly one renderer-switch JSX expression');

  const switchNode = matches[0];
  const selected = mode === 'rnw' ? switchNode.whenTrue : switchNode.whenFalse;
  const edits = [
    { start: declaration.getStart(source), end: declaration.end, replace: '' },
    {
      start: switchNode.getStart(source),
      end: switchNode.end,
      replace: selected.getText(source),
    },
  ].sort((a, b) => b.start - a.start);
  assert(edits[0].start >= edits[1].end, 'Unexpected overlapping TSX edits');

  let variant = original;
  for (const edit of edits) {
    variant = variant.slice(0, edit.start) + edit.replace + variant.slice(edit.end);
  }
  const check = ts.createSourceFile(routePath, variant, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  assert.equal(check.parseDiagnostics.length, 0, mode + ' variant does not parse as TSX');
  assert(!variant.includes('function ' + omitName + '('), 'Unused renderer declaration was retained');
  assert(!variant.includes('<' + omitName), 'Unused renderer still referenced');
  return variant;
}

function collectJs(folder, base = folder) {
  const entries = [];
  for (const dirent of fs.readdirSync(folder, { withFileTypes: true })) {
    const full = path.join(folder, dirent.name);
    if (dirent.isDirectory()) entries.push(...collectJs(full, base));
    else if (full.endsWith('.js')) {
      const data = fs.readFileSync(full);
      entries.push({
        file: path.relative(base, full).replaceAll(path.sep, '/'),
        bytes: data.length,
        gzipBytes: zlib.gzipSync(data, { level: 9 }).length,
        brotliBytes: zlib.brotliCompressSync(data).length,
        sha256: digest(data),
      });
    }
  }
  return entries.sort((a, b) => a.file.localeCompare(b.file));
}

function buildVariant(mode) {
  fs.writeFileSync(routePath, isolatedRoute(mode), 'utf8');
  fs.rmSync(distDir, { recursive: true, force: true });
  execFileSync(process.platform === 'win32' ? 'npx.cmd' : 'npx',
    ['expo', 'export', '-p', 'web'],
    { cwd: mobile, env: { ...process.env, NODE_ENV: 'production', EXPO_NO_TELEMETRY: '1' },
      stdio: 'inherit', timeout: 900000 });
  const assets = collectJs(distDir);
  assert(assets.length > 0, 'Expo export did not produce JS assets');
  const combinedJs = assets.map(({ file }) => fs.readFileSync(path.join(distDir, file), 'utf8')).join('\n');
  const present = mode === 'rnw'
    ? 'React Native Web virtualised transaction list'
    : 'React DOM virtualised transaction table';
  const absent = mode === 'rnw'
    ? 'React DOM virtualised transaction table'
    : 'React Native Web virtualised transaction list';
  assert(combinedJs.includes(present), 'Expected renderer marker missing from exported JS: ' + present);
  assert(!combinedJs.includes(absent), 'The other renderer still appears in exported JS: ' + absent);
  const sum = (key) => assets.reduce((total, item) => total + item[key], 0);
  return {
    renderer: mode,
    jsAssets: assets.length,
    uncompressedBytes: sum('bytes'),
    gzipBytes: sum('gzipBytes'),
    brotliBytes: sum('brotliBytes'),
    assets,
  };
}

let result;
try {
  const rnw = buildVariant('rnw');
  const dom = buildVariant('dom');
  result = {
    generatedAt: new Date().toISOString(),
    methodology: [
      'Independent sequential production Expo static-web exports, not a development-server measurement.',
      'Same commit, Expo app, route, dependency lockfile, shared data and UI logic; the TSX transformer removes the unused renderer declaration and its conditional JSX branch for each build.',
      'All emitted JS assets are counted; gzip and Brotli are summed per asset. Counts include shared application/runtime code, not just the transaction route.',
      'An isolated production bundle does not establish native screen-reader accessibility, Web Vitals, or a standalone React-app comparison.',
      'The original synthetic experiment route is restored in a finally block. Never merge this script/route into production.',
    ],
    environment: {
      node: process.version,
      platform: process.platform,
      architecture: process.arch,
      cpuCount: os.cpus().length,
    },
    baselineRouteSha256: digest(original),
    variants: [rnw, dom],
    domMinusRnw: {
      uncompressedBytes: dom.uncompressedBytes - rnw.uncompressedBytes,
      gzipBytes: dom.gzipBytes - rnw.gzipBytes,
      brotliBytes: dom.brotliBytes - rnw.brotliBytes,
    },
  };
} finally {
  fs.writeFileSync(routePath, original, 'utf8');
}
if (result) {
  fs.writeFileSync(path.join(outDir, 'spike-384-isolated-bundles.json'),
    JSON.stringify(result, null, 2) + '\n');
  console.log('ISOLATED BUNDLES', JSON.stringify({
    baselineRouteSha256: result.baselineRouteSha256,
    variants: result.variants.map(({ renderer, jsAssets, uncompressedBytes, gzipBytes, brotliBytes }) =>
      ({ renderer, jsAssets, uncompressedBytes, gzipBytes, brotliBytes })),
    domMinusRnw: result.domMinusRnw,
  }));
}
