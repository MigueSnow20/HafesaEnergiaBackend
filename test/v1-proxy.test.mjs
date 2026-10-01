import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createV1Proxy } from '../lib/v1-proxy.mjs';

async function listen(handler) {
  const server = http.createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return server;
}
async function close(server) {
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}

test('forwards V1 bodies, query parameters, status and polling headers without overriding public CORS', async () => {
  let received;
  const spring = await listen(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    received = { path: req.url, body: Buffer.concat(chunks).toString(), origin: req.headers.origin };
    res.writeHead(201, { 'Content-Type': 'application/json', 'X-Market-Refresh-Ms': '30000',
      'Access-Control-Allow-Origin': 'http://internal.example' });
    res.end('{"status":"accepted"}');
  });
  const proxy = createV1Proxy(`http://127.0.0.1:${spring.address().port}`);
  const gateway = await listen((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    proxy(req, res);
  });
  try {
    const response = await fetch(`http://127.0.0.1:${gateway.address().port}/api/v1/reports?check=1`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Origin: 'https://frontend.example' },
      body: '{"text":"test"}',
    });
    assert.equal(response.status, 201);
    assert.equal(response.headers.get('X-Market-Refresh-Ms'), '30000');
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
    assert.deepEqual(await response.json(), { status: 'accepted' });
    assert.deepEqual(received, { path: '/api/v1/reports?check=1', body: '{"text":"test"}', origin: undefined });
  } finally { await close(gateway); await close(spring); }
});

test('unavailable Spring returns a controlled error without exposing internal details', async () => {
  const stopped = await listen(() => {});
  const port = stopped.address().port;
  await close(stopped);
  const gateway = await listen(createV1Proxy(`http://127.0.0.1:${port}`));
  try {
    const response = await fetch(`http://127.0.0.1:${gateway.address().port}/api/v1/health`);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { detail: 'Servicio temporalmente no disponible' });
  } finally { await close(gateway); }
});

test('proxy target must remain on loopback', () => {
  assert.throws(() => createV1Proxy('https://petroxpertbackend.fly.dev'));
});
