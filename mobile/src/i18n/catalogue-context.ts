import type { Catalogues, Messages } from './locale-catalogues';

// Expo's Metro context bundles new locale JSON files automatically.
const context = require.context('.', false, /^\.\/[a-z]{2,3}(?:-[A-Z]{2})?\.json$/);

export const catalogues: Catalogues = Object.fromEntries(
  context.keys().map((path) => [path.slice(2, -5), context<Messages>(path)]),
);
