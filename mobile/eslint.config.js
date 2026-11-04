// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettierConfig = require('eslint-config-prettier');

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
    // US-29-05 (#375): app code calls the API through typedApi, whose paths and types come from
    // the contract; the untyped transport stays inside src/api (and its tests).
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/api/**', '**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@/api/client',
              importNames: ['api', 'request'],
              message: 'Call the API through typedApi (@/api/typed-api): its types are generated.',
            },
          ],
        },
      ],
    },
  },
  {
    ignores: ['dist/*'],
  },
]);
