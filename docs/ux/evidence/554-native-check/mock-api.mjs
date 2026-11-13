// Minimal mock of the auth API for the PR #558 iOS check: login → MFA challenge, everything else 401.
import http from 'node:http';

let logins = 0;
http
  .createServer((req, res) => {
    const path = new URL(req.url, 'http://x').pathname;
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      console.log(new Date().toISOString(), req.method, path, path.endsWith('/login') ? `#${++logins} ${body.replace(/"password":"[^"]*"/, m => '"password":<len ' + (m.length - 13) + '>')}` : '');
      if (req.method === 'POST' && path === '/api/v1/auth/login') {
        setTimeout(() => {
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ mfaRequired: true, mfaChallengeToken: 'challenge-1' }));
        }, 1500);
        return;
      }
      res.writeHead(401, { 'content-type': 'application/problem+json' });
      res.end(JSON.stringify({ type: 'about:blank', title: 'Unauthorized', status: 401, code: 'UNAUTHORIZED' }));
    });
  })
  .listen(8099, '127.0.0.1', () => console.log('mock api on 8099'));
