// Serves the current one-time code of TMW_TOTP_SECRET on localhost, for Maestro's runScript.
import { createServer } from 'node:http';
import { totp } from './totp.mjs';
createServer((_, res) => res.end(totp(process.env.TMW_TOTP_SECRET))).listen(8097, '127.0.0.1');
