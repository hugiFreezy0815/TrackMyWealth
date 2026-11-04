/**
 * An exact API decimal - money, quantity, unit price, FX rate, percentage - exactly as the backend
 * sends it: a plain decimal string such as "12345678.1234567891" (#176). The generated API types
 * (schema.d.ts, #375) give every `format: decimal` field this type, never `number`: a JavaScript
 * number is an IEEE-754 double and rounds the value. Keep it as received, send it back unchanged,
 * and do arithmetic only through a decimal library.
 */
export type DecimalString = string;
