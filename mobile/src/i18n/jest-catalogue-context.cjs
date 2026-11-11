const { readdirSync, readFileSync } = require('node:fs');
const { join } = require('node:path');

// Jest runs outside Metro. Read the same catalogue files from disk.
exports.catalogues = Object.fromEntries(
  readdirSync(__dirname)
    .filter((name) => /^[a-z]{2,3}(?:-[A-Z]{2})?\.json$/.test(name))
    .map((name) => [name.slice(0, -5), JSON.parse(readFileSync(join(__dirname, name), 'utf8'))]),
);
