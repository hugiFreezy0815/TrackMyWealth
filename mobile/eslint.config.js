// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettierConfig = require('eslint-config-prettier');

// Flat config replaces a rule's options per file instead of merging them, so every block that sets
// no-restricted-imports or no-restricted-syntax repeats the shared entries below; otherwise a later
// block would silently drop an earlier block's checks for its files.

// US-29-05 (#375): app code calls the API through typedApi, whose paths and types come from the
// contract; the untyped transport stays inside src/api (and its tests).
const TYPED_API_ONLY = {
  name: '@/api/client',
  importNames: ['api', 'request'],
  message: 'Call the API through typedApi (@/api/typed-api): its types are generated.',
};
// FR-CLI-005: an amount rendered without PrivacyAmount would ignore "Hide amounts".
const PRIVATE_AMOUNTS_ONLY = {
  name: '@/format/amount',
  importNames: ['formatAmount', 'a11yAmount'],
  message:
    'Render money with PrivacyAmount (@/features/privacy/privacy-amount): it honours "Hide amounts" (FR-CLI-005).',
};

// #452: a newer self-hosted backend can send an enum value this app does not know, and the
// generated union cannot stop that at runtime. ESLint names the file and line of each break:
// - every switch has a default branch (ESLint cannot tell an API enum switch from another, so the
//   rule covers all of them; the app has no switch that would need an exception);
// - that default does not throw, which would crash the screen on the new value: it renders the
//   neutral fallback instead (labelFor in @/api/enum-label);
// - a display map (a *_LABELS, *_ICONS or *_COLORS constant) is read through labelFor, never
//   indexed directly, so an unknown value gets the fallback instead of undefined.
const ENUM_FALLBACK_SELECTORS = [
  {
    selector:
      'SwitchCase:not([test]) > ThrowStatement, SwitchCase:not([test]) > BlockStatement > ThrowStatement',
    message:
      'A newer backend can send a value this switch does not know (#452): render the neutral fallback (labelFor) instead of throwing.',
  },
  {
    selector:
      'MemberExpression[computed=true][object.name=/^[A-Z][A-Z0-9_]*_(LABELS|ICONS|COLORS)$/]',
    message:
      'Read an API enum display map through labelFor (@/api/enum-label), so an unknown value gets the neutral fallback (#452).',
  },
];
const NO_LITERAL_UI_TEXT = {
  selector: 'JSXText[value=/\\S/]',
  message: 'Use an ICU message from src/i18n instead of a literal UI string.',
};
const TESTS = '**/*.test.{ts,tsx}';
// Screens that render only catalogue copy (sprint-7 rule "no literal UI strings"). Legacy
// reference-data and imports are migrated when their own sprint-7 tickets restyle them.
const CATALOGUE_ONLY_FILES = [
  'src/app/**/*.tsx',
  'src/features/shell/**/*.tsx',
  'src/features/privacy/**/*.tsx',
  'src/features/auth/**/*.tsx',
];

module.exports = defineConfig([
  expoConfig,
  // Disables ESLint's own stylistic/formatting rules so ESLint (logic/quality) and Prettier
  // (formatting) never disagree about the same line - must come after expoConfig.
  prettierConfig,
  {
    // eslint-plugin-react's "detect" version lookup calls context.getFilename(),
    // which ESLint 10's flat-config Linter no longer provides, crashing every lint
    // run. Pin the version instead so detection never runs.
    // https://github.com/jsx-eslint/eslint-plugin-react/issues/3910
    settings: {
      react: { version: require('./package.json').dependencies.react },
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/api/**', TESTS],
    rules: {
      'no-restricted-imports': ['error', { paths: [TYPED_API_ONLY, PRIVATE_AMOUNTS_ONLY] }],
    },
  },
  {
    // The one component allowed to format money; it applies the privacy mask itself.
    files: ['src/features/privacy/privacy-amount.tsx'],
    rules: {
      'no-restricted-imports': ['error', { paths: [TYPED_API_ONLY] }],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [TESTS, 'src/api/schema.d.ts', 'src/api/enum-label.ts'],
    rules: {
      'default-case': 'error',
      'no-restricted-syntax': ['error', ...ENUM_FALLBACK_SELECTORS],
    },
  },
  {
    files: CATALOGUE_ONLY_FILES,
    ignores: [TESTS, 'src/app/reference-data.tsx'],
    rules: {
      'no-restricted-syntax': ['error', ...ENUM_FALLBACK_SELECTORS, NO_LITERAL_UI_TEXT],
    },
  },
  {
    ignores: ['dist/*'],
  },
]);
