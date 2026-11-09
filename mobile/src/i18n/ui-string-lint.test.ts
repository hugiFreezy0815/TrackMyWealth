// ADR 0008 / #486: feature screens render ICU messages, never literal copy. Linting snippets
// through the project's ESLint config proves the rule covers any new folder under src/features/
// and that it keeps the #452 enum selectors (flat config replaces no-restricted-syntax options).
// ESLint runs in a child Node process, as in src/api/enum-lint.test.ts (see the notes there).

type ChildProcess = {
  spawnSync: (
    command: string,
    args: string[],
    options: { cwd: string; input: string; encoding: 'utf8' },
  ) => { status: number | null; stdout: string; stderr: string };
};

const root = process.cwd();
const screenFile = `${root}/src/features/overview/total-card.tsx`;
const legacyFile = `${root}/src/features/reference-data/legacy-card.tsx`;
const UI_STRING = 'Use an ICU message from src/i18n instead of a literal UI string.';

const LINT_SNIPPETS = `
const { ESLint } = require('eslint');
let input = '';
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', async () => {
  const eslint = new ESLint({ cwd: process.cwd() });
  const results = [];
  for (const { code, filePath } of JSON.parse(input)) {
    const [result] = await eslint.lintText(code, { filePath });
    results.push(result.messages.map((m) => m.message));
  }
  process.stdout.write(JSON.stringify(results));
});
`;

const header =
  "import { Text } from 'react-native';\nimport { FormattedMessage, useIntl } from 'react-intl';\n";
const SNIPPETS = {
  jsxText: [screenFile, `${header}export const Total = () => <Text>Net worth</Text>;\n`],
  expression: [screenFile, `${header}export const Total = () => <Text>{'Net worth'}</Text>;\n`],
  template: [screenFile, `${header}export const Total = () => <Text>{\`Total \${1}\`}</Text>;\n`],
  a11yProp: [
    screenFile,
    `${header}export const Total = () => <Text accessibilityLabel="Net worth" />;\n`,
  ],
  umlaut: [screenFile, `${header}export const Total = () => <Text>Übersicht</Text>;\n`],
  messages: [
    screenFile,
    `${header}export function Total() {\n  const intl = useIntl();\n  return (\n    <Text accessibilityLabel={intl.formatMessage({ id: 'nav.overview' })}>\n      <FormattedMessage id="nav.overview" />{' '}·\n    </Text>\n  );\n}\n`,
  ],
  enumThrow: [
    screenFile,
    "export function label(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'c';\n    default:\n      throw new Error('unknown');\n  }\n}\n",
  ],
  legacy: [legacyFile, `${header}export const Card = () => <Text>Categories</Text>;\n`],
} as const;

let results: Record<keyof typeof SNIPPETS, string[]>;

beforeAll(() => {
  const { spawnSync } = jest.requireActual<ChildProcess>('node:child_process');
  const names = Object.keys(SNIPPETS) as (keyof typeof SNIPPETS)[];
  const child = spawnSync(process.execPath, ['-e', LINT_SNIPPETS], {
    cwd: root,
    input: JSON.stringify(
      names.map((name) => ({ filePath: SNIPPETS[name][0], code: SNIPPETS[name][1] })),
    ),
    encoding: 'utf8',
  });
  if (child.status !== 0) {
    throw new Error(`ESLint failed to run (exit ${child.status}):\n${child.stderr}`);
  }
  const output = JSON.parse(child.stdout) as string[][];
  results = Object.fromEntries(names.map((name, index) => [name, output[index]])) as typeof results;
}, 60_000);

describe('literal UI string lint rule (#486)', () => {
  it.each(['jsxText', 'expression', 'template', 'a11yProp', 'umlaut'] as const)(
    'fails %s copy in a new feature folder',
    (name) => {
      expect(results[name]).toContain(UI_STRING);
    },
  );

  it('passes copy rendered from the catalogue and punctuation', () => {
    expect(results.messages).toEqual([]);
  });

  it('keeps the #452 enum rules in feature screens', () => {
    expect(results.enumThrow.some((message) => message.includes('#452'))).toBe(true);
  });

  it('exempts only the listed legacy screens until they migrate', () => {
    expect(results.legacy).not.toContain(UI_STRING);
  });
});
