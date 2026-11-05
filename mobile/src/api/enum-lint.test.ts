/**
 * @jest-environment node
 */

// #452: the lint rules that keep screens working when a newer backend sends an enum value this
// app does not know. Linting snippets through the project's own ESLint config proves each rule is
// active for app code and that its report names the file.
//
// ESLint runs in a child Node process, once for all snippets: in-process, its async work can
// touch jest-expo's lazily installed globals after the test file has finished, which Jest rejects
// ("require a file outside of the scope of the test code"). The child also loads
// eslint.config.js exactly as `expo lint` does.

type ChildProcess = {
  spawnSync: (
    command: string,
    args: string[],
    options: { cwd: string; input: string; encoding: 'utf8' },
  ) => { status: number | null; stdout: string; stderr: string };
};

// Jest runs from mobile/ (its rootDir); the app's tsconfig has no Node types for path/__dirname.
const root = process.cwd();
const screenFile = `${root}/src/features/example/status-badge.tsx`;

const LINT_SNIPPETS = `
const { ESLint } = require('eslint');
let input = '';
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', async () => {
  const eslint = new ESLint({ cwd: process.cwd() });
  const results = [];
  for (const { code, filePath } of JSON.parse(input)) {
    const [result] = await eslint.lintText(code, { filePath });
    results.push({ file: result.filePath, rules: result.messages.map((m) => m.ruleId) });
  }
  process.stdout.write(JSON.stringify(results));
});
`;

const SNIPPETS = {
  noDefault:
    "export function label(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'Current';\n  }\n  return '';\n}\n",
  throwingDefault:
    "export function label(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'Current';\n    default:\n      throw new Error('unknown');\n  }\n}\n",
  throwingDefaultBlock:
    "export function label(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'Current';\n    default: {\n      throw new Error('unknown');\n    }\n  }\n}\n",
  indexedMap:
    "const STATUS_LABELS: Record<string, string> = { CURRENT: 'Current' };\nexport const label = (status: string) => STATUS_LABELS[status];\n",
  safe: "import { labelFor, UNKNOWN_ENUM_LABELS } from '@/api/enum-label';\n\nconst STATUS_LABELS = { CURRENT: 'Current' };\nexport const label = (status: string) =>\n  labelFor(STATUS_LABELS, status, UNKNOWN_ENUM_LABELS.en);\nexport function tone(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'green';\n    default:\n      return 'grey';\n  }\n}\n",
} as const;

type Result = { file: string; rules: (string | null)[] };
let results: Record<keyof typeof SNIPPETS, Result>;

beforeAll(() => {
  const { spawnSync } = jest.requireActual<ChildProcess>('node:child_process');
  const names = Object.keys(SNIPPETS) as (keyof typeof SNIPPETS)[];
  const child = spawnSync(process.execPath, ['-e', LINT_SNIPPETS], {
    cwd: root,
    input: JSON.stringify(names.map((name) => ({ code: SNIPPETS[name], filePath: screenFile }))),
    encoding: 'utf8',
  });
  if (child.status !== 0) {
    throw new Error(`ESLint failed to run (exit ${child.status}):\n${child.stderr}`);
  }
  const output = JSON.parse(child.stdout) as Result[];
  results = Object.fromEntries(names.map((name, index) => [name, output[index]])) as typeof results;
}, 60_000);

describe('API enum lint rules (#452)', () => {
  it('fails a switch without a default branch and names the file', () => {
    expect(results.noDefault.file).toBe(screenFile);
    expect(results.noDefault.rules).toContain('default-case');
  });

  it('fails a default branch that throws on an unknown value', () => {
    expect(results.throwingDefault.rules).toContain('no-restricted-syntax');
    expect(results.throwingDefaultBlock.rules).toContain('no-restricted-syntax');
  });

  it('fails a display map indexed without labelFor', () => {
    expect(results.indexedMap.rules).toContain('no-restricted-syntax');
  });

  it('passes a switch with a fallback default and a map read through labelFor', () => {
    expect(results.safe.rules).toEqual([]);
  });
});
