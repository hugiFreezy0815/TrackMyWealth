// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettierConfig = require('eslint-config-prettier');

// Flat config replaces no-restricted-syntax options per file.
const ACCESSIBLE_STATE_SYNTAX = {
  selector: 'JSXAttribute[name.name="accessibilityState"]',
  message: 'Use aria-* state props so React Native Web exposes the state (#540).',
};

const ENUM_FALLBACK_SYNTAX = [
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

const LITERAL_UI_STRING_MESSAGE =
  'Use an ICU message from src/i18n instead of a literal UI string.';
const LITERAL_UI_STRING_SYNTAX = [
  'JSXText[value=/[A-Za-zÀ-ÿ]/]',
  'JSXExpressionContainer > Literal[value=/[A-Za-zÀ-ÿ]/]',
  'JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/[A-Za-zÀ-ÿ]/]',
  'JSXAttribute[name.name=/^(accessibilityLabel|accessibilityHint|aria-label|placeholder|title|label)$/] > Literal',
].map((selector) => ({ selector, message: LITERAL_UI_STRING_MESSAGE }));

// Flat config also replaces no-restricted-imports options per file, so all entries live here and
// every block that sets the rule lists the ones its files need.
// US-29-05 (#375): app code calls the API through typedApi, whose paths and types come from the
// contract; the untyped transport stays inside src/api (and its tests).
const TYPED_API_ONLY = {
  name: '@/api/client',
  importNames: ['api', 'request'],
  message: 'Call the API through typedApi (@/api/typed-api): its types are generated.',
};
// FR-CLI-005 / #487: an amount rendered without PrivacyAmount would ignore "Hide amounts".
const PRIVATE_AMOUNTS_ONLY = {
  name: '@/format/amount',
  importNames: ['formatAmount', 'a11yAmount'],
  message:
    'Render money with PrivacyAmount (@/features/privacy/privacy-amount): it honours "Hide amounts" (FR-CLI-005).',
};
// UX-02 §7: "Hide amounts" also masks percentages, units and rates (#493).
const PRIVATE_FIGURES_ONLY = {
  name: '@/format/figure',
  importNames: ['formatFigure', 'a11yFigure'],
  message:
    'Render units, percentages and rates with Figure (@/components/shared): it honours "Hide amounts" (FR-CLI-005).',
};

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
    ignores: ['src/api/**', '**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        { paths: [TYPED_API_ONLY, PRIVATE_AMOUNTS_ONLY, PRIVATE_FIGURES_ONLY] },
      ],
    },
  },
  {
    // The amount and figure components format numbers; they apply the privacy mask themselves.
    // PrivacyAmount (#487) renders through the shared Amount (#488), so both honour "Hide amounts".
    files: [
      'src/components/shared/amount.tsx',
      'src/components/shared/figure.tsx',
      'src/features/privacy/privacy-amount.tsx',
    ],
    rules: {
      'no-restricted-imports': ['error', { paths: [TYPED_API_ONLY] }],
    },
  },
  {
    // #452: a newer self-hosted backend can send an enum value this app does not know, and the
    // generated union cannot stop that at runtime. ESLint names the file and line of each break:
    // - every switch has a default branch (ESLint cannot tell an API enum switch from another, so
    //   the rule covers all of them; the app has no switch that would need an exception);
    // - that default does not throw, which would crash the screen on the new value: it renders the
    //   neutral fallback instead (labelFor in @/api/enum-label);
    // - a display map (a *_LABELS, *_ICONS or *_COLORS constant) is read through labelFor, never
    //   indexed directly, so an unknown value gets the fallback instead of undefined.
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['**/*.test.{ts,tsx}', 'src/api/schema.d.ts', 'src/api/enum-label.ts'],
    rules: {
      'default-case': 'error',
      'no-restricted-syntax': ['error', ACCESSIBLE_STATE_SYNTAX, ...ENUM_FALLBACK_SYNTAX],
    },
  },
  {
    // ADR 0008 / #486: feature screens render ICU messages from src/i18n; literal copy (JSX text,
    // accessibility props, string expressions) belongs in the catalogues. Flat config replaces a
    // rule's options per file instead of merging them, so this block repeats the #452 selectors.
    // The legacy screens below predate the catalogues; their sprint-7 tickets migrate them and
    // delete the entry here. Routes render screens too (#487 migrated auth and the shell routes),
    // and the shared components (#488) follow the same rule.
    files: [
      'src/features/**/*.{ts,tsx,jsx}',
      'src/app/**/*.{ts,tsx,jsx}',
      'src/components/shared/**/*.{ts,tsx}',
    ],
    ignores: [
      '**/*.test.{ts,tsx}',
      'src/features/reference-data/**',
      'src/features/imports/**',
      // The #488 component gallery: development builds only, synthetic sample copy.
      'src/app/dev/**',
    ],
    rules: {
      'no-restricted-syntax': [
        'error',
        ACCESSIBLE_STATE_SYNTAX,
        ...ENUM_FALLBACK_SYNTAX,
        ...LITERAL_UI_STRING_SYNTAX,
      ],
    },
  },
  {
    ignores: ['dist/*'],
  },
]);
