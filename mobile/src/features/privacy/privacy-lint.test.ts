// #487 lint guards: money is rendered only through PrivacyAmount (FR-CLI-005), and route files
// render catalogue copy only (src/i18n/ui-string-lint.test.ts covers src/features). Flat config
// replaces a rule's options per file, so these snippets also prove the #452 enum checks and the
// typedApi rule survive where the extra rules apply. Runs ESLint in a child process, as
// src/api/enum-lint.test.ts does.

type ChildProcess = {
  spawnSync: (
    command: string,
    args: string[],
    options: { cwd: string; input: string; encoding: 'utf8' },
  ) => { status: number | null; stdout: string; stderr: string };
};

const root = process.cwd();
const SHELL_FILE = `${root}/src/features/shell/example-card.tsx`;
const ROUTE_FILE = `${root}/src/app/example.tsx`;
const PRIVACY_AMOUNT_FILE = `${root}/src/features/privacy/privacy-amount.tsx`;
const FIGURE_FILE = `${root}/src/components/shared/figure.tsx`;

const LINT_SNIPPETS = `
const { ESLint } = require('eslint');
let input = '';
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', async () => {
  const eslint = new ESLint({ cwd: process.cwd() });
  const results = [];
  for (const { code, filePath } of JSON.parse(input)) {
    const [result] = await eslint.lintText(code, { filePath });
    results.push({ rules: result.messages.map((m) => m.ruleId) });
  }
  process.stdout.write(JSON.stringify(results));
});
`;

const SNIPPETS = {
  rawAmountInScreen: {
    filePath: SHELL_FILE,
    code: "import { formatAmount } from '@/format/amount';\n\nexport const total = formatAmount('1.00', 'CHF', 'en');\n",
  },
  rawAmountInPrivacyAmount: {
    filePath: PRIVACY_AMOUNT_FILE,
    code: "import { formatAmount } from '@/format/amount';\n\nexport const total = formatAmount('1.00', 'CHF', 'en');\n",
  },
  // #493: units, percentages and rates are masked by "Hide amounts" too (UX-02 §7).
  rawFigureInScreen: {
    filePath: SHELL_FILE,
    code: "import { formatFigure } from '@/format/figure';\n\nexport const gain = formatFigure('1.00', 'percent', 'en');\n",
  },
  rawFigureInFigure: {
    filePath: FIGURE_FILE,
    code: "import { formatFigure } from '@/format/figure';\n\nexport const gain = formatFigure('1.00', 'percent', 'en');\n",
  },
  untypedApiInPrivacyAmount: {
    filePath: PRIVACY_AMOUNT_FILE,
    code: "import { api } from '@/api/client';\n\nexport const client = api;\n",
  },
  literalTextInRoute: {
    filePath: ROUTE_FILE,
    code: "import { Text } from 'react-native';\n\nexport default function Example() {\n  return <Text>Overview</Text>;\n}\n",
  },
  throwingDefaultInRoute: {
    filePath: ROUTE_FILE,
    code: "export function tone(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'green';\n    default:\n      throw new Error('unknown');\n  }\n}\n",
  },
  catalogueTextInRoute: {
    filePath: ROUTE_FILE,
    code: "import { Text } from 'react-native';\nimport { useIntl } from 'react-intl';\n\nexport default function Example() {\n  const intl = useIntl();\n  return <Text>{intl.formatMessage({ id: 'nav.overview' })}</Text>;\n}\n",
  },
} as const;

type Name = keyof typeof SNIPPETS;
let rules: Record<Name, (string | null)[]>;

beforeAll(() => {
  const { spawnSync } = jest.requireActual<ChildProcess>('node:child_process');
  const names = Object.keys(SNIPPETS) as Name[];
  const child = spawnSync(process.execPath, ['-e', LINT_SNIPPETS], {
    cwd: root,
    input: JSON.stringify(names.map((name) => SNIPPETS[name])),
    encoding: 'utf8',
  });
  if (child.status !== 0) {
    throw new Error(`ESLint failed to run (exit ${child.status}):\n${child.stderr}`);
  }
  const output = JSON.parse(child.stdout) as { rules: (string | null)[] }[];
  rules = Object.fromEntries(
    names.map((name, index) => [name, output[index].rules]),
  ) as typeof rules;
}, 60_000);

describe('sprint-7 lint guards', () => {
  it('fails a screen that formats money without PrivacyAmount', () => {
    expect(rules.rawAmountInScreen).toContain('no-restricted-imports');
  });

  it('fails a screen that formats units, percentages or rates without Figure', () => {
    expect(rules.rawFigureInScreen).toContain('no-restricted-imports');
    expect(rules.rawFigureInFigure).toEqual([]);
  });

  it('lets PrivacyAmount format money but still requires typedApi there', () => {
    expect(rules.rawAmountInPrivacyAmount).toEqual([]);
    expect(rules.untypedApiInPrivacyAmount).toContain('no-restricted-imports');
  });

  it('fails a literal UI string in a route screen', () => {
    expect(rules.literalTextInRoute).toContain('no-restricted-syntax');
  });

  it('keeps the #452 enum fallback rule in files with the literal-string rule', () => {
    expect(rules.throwingDefaultInRoute).toContain('no-restricted-syntax');
  });

  it('passes catalogue copy', () => {
    expect(rules.catalogueTextInRoute).toEqual([]);
  });
});
