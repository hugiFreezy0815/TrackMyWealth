// Prints the current TOTP code (RFC 6238, SHA-1, 30 s, 6 digits) for a base32 secret.
import { createHmac } from 'node:crypto';
export function totp(secret, at = Date.now()) {
  const bits = [...secret.replace(/=+$/, '').toUpperCase()].map((c) => 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'.indexOf(c).toString(2).padStart(5, '0')).join('');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30000)));
  const mac = createHmac('sha1', key).update(counter).digest();
  const o = mac[19] & 15;
  return String((mac.readUInt32BE(o) & 0x7fffffff) % 1e6).padStart(6, '0');
}
if (import.meta.url.endsWith(process.argv[1]) && process.argv[2]) console.log(totp(process.argv[2]));
