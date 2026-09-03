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
    ignores: ['dist/*'],
  },
]);
