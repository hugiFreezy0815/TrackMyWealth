// Native check for #574: a pass-through proxy in front of the local API that records every
// statement upload's Idempotency-Key and, while the file DROP_FLAG exists, lets the next upload
// reach the server and then drops its answer (the connection is destroyed after the server has
// committed), as on a broken mobile connection. The flag is removed after one drop.
// Usage: node lossy-proxy.mjs <listenPort> <upstreamPort> <logFile> <dropFlagFile>
import http from 'node:http';
import { appendFileSync, existsSync, unlinkSync } from 'node:fs';

const [listenPort, upstreamPort, logFile, dropFlag] = process.argv.slice(2);
const UPLOAD = /^\/api\/v1\/accounts\/[^/]+\/imports$/;
const log = (entry) => appendFileSync(logFile, JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n');

http
  .createServer((req, res) => {
    const isUpload = req.method === 'POST' && UPLOAD.test(req.url.split('?')[0]);
    log({ arrived: req.method + ' ' + req.url.split('?')[0], contentType: req.headers['content-type'] ?? null, length: req.headers['content-length'] ?? null, uuid: req.headers['idempotency-key'] ?? null });
    req.on('aborted', () => log({ clientAborted: req.method + ' ' + req.url.split('?')[0] }));
    const drop = isUpload && existsSync(dropFlag);
    if (drop) unlinkSync(dropFlag);
    const upstream = http.request(
      { host: '127.0.0.1', port: upstreamPort, method: req.method, path: req.url, headers: req.headers },
      (answer) => {
        if (!isUpload) {
          res.writeHead(answer.statusCode, answer.headers);
          answer.pipe(res);
          return;
        }
        const chunks = [];
        answer.on('data', (c) => chunks.push(c));
        answer.on('end', () => {
          const body = Buffer.concat(chunks);
          let batchId = null;
          try {
            batchId = JSON.parse(body.toString('utf8')).id ?? null;
          } catch {
            // a problem body or no JSON: no batch id
          }
          log({ upload: true, uuid: req.headers['idempotency-key'] ?? null, status: answer.statusCode, batchId, dropped: drop });
          if (drop) {
            req.socket.destroy(); // the server committed; the app never gets the answer
            return;
          }
          res.writeHead(answer.statusCode, answer.headers);
          res.end(body);
        });
      },
    );
    upstream.on('error', (e) => {
      log({ error: e.message, path: req.url });
      res.destroy();
    });
    req.pipe(upstream);
  })
  .listen(Number(listenPort), '127.0.0.1', () => log({ listening: listenPort, upstream: upstreamPort }));
