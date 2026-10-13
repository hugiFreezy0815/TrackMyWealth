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
    // eslint-import-resolver-typescript cannot resolve Expo 58's extended tsconfig in ESLint 10.
    // TypeScript's strict `tsc --noEmit` check in CI still verifies actual module resolution.
    // Preserve other ESLint import checks rather than suppressing npm dependency validation.
    rules: {
      'import/namespace': 'off',
      'import/no-unresolved': 'off',
    },
  },
  {
    ignores: ['dist/*'],
  },
]);
