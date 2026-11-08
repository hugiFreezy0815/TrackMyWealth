// US-29-05 (#375): generates the app's API types from the committed contract. Never edit the
// output by hand; change the backend, regenerate api/openapi.yaml, then run `npm run api:types`.
//
//   src/api/schema.d.ts               paths, operations and schemas of api/openapi.yaml
//                                     (openapi-typescript); a `format: decimal` field is
//                                     DecimalString, never number
//   src/api/problem-codes.generated.ts the known problem codes with their client reaction, and the
//                                     known import row codes (api/problem-codes.json)
//
// `--check` writes nothing: it fails, naming each stale file, when a file differs from what the
// contract generates (Mobile Web CI).
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import openapiTS, { astToString } from 'openapi-typescript';
import * as prettier from 'prettier';
import ts from 'typescript';

const mobile = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const CONTRACT = path.resolve(mobile, '../api/openapi.yaml');
const REGISTRY = path.resolve(mobile, '../api/problem-codes.json');
const SCHEMA_OUT = 'src/api/schema.d.ts';
const CODES_OUT = 'src/api/problem-codes.generated.ts';

const HEADER = (source) =>
  `// Generated from ${source} by \`npm run api:types\` (scripts/generate-api-types.mjs).\n` +
  '// Do not edit by hand: change the backend, regenerate the contract, then run the script.\n';

const decimal = ts.factory.createTypeReferenceNode('DecimalString');
const nullType = ts.factory.createLiteralTypeNode(ts.factory.createNull());

async function schemaFile() {
  const ast = await openapiTS(pathToFileURL(CONTRACT), {
    transform(schema) {
      if (schema.format !== 'decimal') return undefined;
      const types = Array.isArray(schema.type) ? schema.type : [schema.type];
      return types.includes('null') || schema.nullable
        ? ts.factory.createUnionTypeNode([decimal, nullType])
        : decimal;
    },
  });
  const text = astToString(ast);
  // Every decimal - property, parameter, array item - must have become DecimalString; a contract
  // shape the transform does not see would otherwise slip through as a plain number or string.
  const decimals = (await readFile(CONTRACT, 'utf8')).match(/format: decimal\b/g)?.length ?? 0;
  const typed = text.match(/\bDecimalString\b/g)?.length ?? 0;
  if (typed !== decimals) {
    throw new Error(
      `api/openapi.yaml has ${decimals} decimal fields, but ${typed} became DecimalString`,
    );
  }
  return HEADER('api/openapi.yaml') + "import type { DecimalString } from './decimal';\n\n" + text;
}

async function codesFile() {
  const registry = JSON.parse(await readFile(REGISTRY, 'utf8'));
  const reactions = Object.entries(registry.problemCodes)
    .map(([code, { reaction }]) => `  ${code}: '${reaction}',`)
    .join('\n');
  const rowCodes = Object.keys(registry.importRowCodes)
    .map((code) => `  '${code}',`)
    .join('\n');
  return `${HEADER('api/problem-codes.json')}
/** Every problem code this app version knows, with the client reaction the contract gives it. */
export const PROBLEM_CODE_REACTIONS = {
${reactions}
} as const;

/** Every import row code this app version knows. */
export const IMPORT_ROW_CODES = [
${rowCodes}
] as const;
`;
}

async function formatted(file, text) {
  const options = await prettier.resolveConfig(path.join(mobile, file));
  return prettier.format(text, { ...options, filepath: path.join(mobile, file) });
}

async function current(file) {
  try {
    return await readFile(path.join(mobile, file), 'utf8');
  } catch {
    return null;
  }
}

const check = process.argv.includes('--check');
const outputs = [
  [SCHEMA_OUT, await formatted(SCHEMA_OUT, await schemaFile())],
  [CODES_OUT, await formatted(CODES_OUT, await codesFile())],
];
const stale = [];
for (const [file, text] of outputs) {
  if ((await current(file)) === text) continue;
  if (check) stale.push(file);
  else await writeFile(path.join(mobile, file), text);
}
if (stale.length > 0) {
  for (const file of stale) {
    console.error(`mobile/${file} is stale: it differs from what the contract generates.`);
  }
  console.error('Run `npm run api:types` in mobile/ and commit the result.');
  process.exit(1);
}
