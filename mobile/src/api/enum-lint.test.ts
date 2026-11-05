/**
 * @jest-environment node
 */
import { ESLint } from 'eslint';

// #452: the lint rules that keep screens working when a newer backend sends an enum value this
// app does not know. Linting snippets through the project's own config proves each rule is active
// for app code and that its report names the file.
// Jest runs from mobile/ (its rootDir); the app's tsconfig has no Node types for path/__dirname.
const root = process.cwd();
// Jest cannot run ESLint's dynamic import() of the config file, so the same config is passed in.
const eslint = new ESLint({
  cwd: root,
  overrideConfigFile: true,
  overrideConfig: jest.requireActual(`${root}/eslint.config.js`),
});
const screenFile = `${root}/src/features/example/status-badge.tsx`;

async function messagesFor(code: string, filePath = screenFile) {
  const [result] = await eslint.lintText(code, { filePath });
  return { file: result.filePath, rules: result.messages.map((message) => message.ruleId) };
}

describe('API enum lint rules (#452)', () => {
  jest.setTimeout(30_000);

  it('fails a switch without a default branch and names the file', async () => {
    const { file, rules } = await messagesFor(
      "export function label(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'Current';\n  }\n  return '';\n}\n",
    );
    expect(file).toBe(screenFile);
    expect(rules).toContain('default-case');
  });

  it('fails a default branch that throws on an unknown value', async () => {
    for (const body of [
      "throw new Error('unknown');",
      "{\n      throw new Error('unknown');\n    }",
    ]) {
      const { rules } = await messagesFor(
        `export function label(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'Current';\n    default:\n      ${body}\n  }\n}\n`,
      );
      expect(rules).toContain('no-restricted-syntax');
    }
  });

  it('fails a display map indexed without labelFor', async () => {
    const { rules } = await messagesFor(
      "const STATUS_LABELS: Record<string, string> = { CURRENT: 'Current' };\nexport const label = (status: string) => STATUS_LABELS[status];\n",
    );
    expect(rules).toContain('no-restricted-syntax');
  });

  it('passes a switch with a fallback default and a map read through labelFor', async () => {
    const { rules } = await messagesFor(
      "import { labelFor, UNKNOWN_ENUM_LABELS } from '@/api/enum-label';\n\nconst STATUS_LABELS = { CURRENT: 'Current' };\nexport const label = (status: string) =>\n  labelFor(STATUS_LABELS, status, UNKNOWN_ENUM_LABELS.en);\nexport function tone(status: string): string {\n  switch (status) {\n    case 'CURRENT':\n      return 'green';\n    default:\n      return 'grey';\n  }\n}\n",
    );
    expect(rules).toEqual([]);
  });
});
