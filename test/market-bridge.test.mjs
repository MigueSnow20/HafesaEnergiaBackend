import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createMarketClient } from '../lib/market-client.mjs';
import { installMarketRoutes } from '../lib/market-routes.mjs';

const snapshot = () => [
  ['GASOIL', 'G.V26', 700], ['GASOLINE_RBOB', 'RB.X26', 2.1], ['EUR_USD', 'M6E.Z26', 1.17],
].map(([code, symbol, price]) => ({ code, symbol, price, fetchedAt: new Date().toISOString(),
  stale: false, sourceStatus: 'OK', source: 'IRONBEAM_WEB', priceField: 'LAST' }));

test('aggregate mapping, exact legacy contracts and concurrent deduplication', async () => {
  let calls = 0;
  const payload = snapshot();
  const read = createMarketClient({ baseUrl: 'http://127.0.0.1:8080', token: 'test-only',
    fetchImpl: async (url, options) => {
      calls++;
      assert.equal(url.pathname, '/internal/legacy-markets');
      assert.equal(options.headers.Authorization, 'Bearer test-only');
      await new Promise(resolve => setTimeout(resolve, 10));
      return Response.json(payload);
    } });
  const routes = new Map();
  installMarketRoutes({ get: (path, handler) => routes.set(path, handler) }, read);
  const respond = async path => {
    let body;
    const res = { setHeader() {}, json(value) { body = value; return this; }, status() { return this; } };
    await routes.get(path)({}, res);
    return body;
  };
  const [gasoil, gasolina, fx, all] = await Promise.all(
    [...routes.keys()].map(respond));
  assert.equal(calls, 1);
  assert.deepEqual(gasoil, { gasoil: 700, textoOriginal: '700', cached: false, obtenidoEn: payload[0].fetchedAt });
  assert.equal(gasolina.gasolina, 2.1);
  assert.equal(fx.tipoCambio, 1.17);
  assert.deepEqual(Object.keys(all), ['gasoil', 'gasolina', 'tipoCambio', 'cached', 'obtenidoEn']);
  assert.equal((await respond('/scrape-gasoil')).cached, true);
  assert.equal(calls, 1);
});

test('rejects missing prices, wrong symbols/source, invalid dates and degraded captures', async () => {
  for (const change of [{ price: null }, { symbol: 'G.X26' }, { source: 'INVESTING' },
    { fetchedAt: 'bad' }, { stale: true }, { sourceStatus: 'ERROR' }]) {
    const payload = snapshot();
    Object.assign(payload[0], change);
    const read = createMarketClient({ baseUrl: 'https://example.invalid', token: 'test-only',
      fetchImpl: async () => Response.json(payload) });
    await assert.rejects(read, /MARKET_API_(INVALID_PAYLOAD|STALE_OR_ERROR)/);
  }
});

test('real HTTP timeout and unavailable Spring fail without a fabricated price', async () => {
  const server = http.createServer(() => {});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const read = createMarketClient({ baseUrl: origin, token: 'test-only', timeoutMs: 50 });
  try { await assert.rejects(read, /MARKET_API_UNAVAILABLE/); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  await assert.rejects(read, /MARKET_API_UNAVAILABLE/);
  const routes = new Map();
  installMarketRoutes({ get: (path, handler) => routes.set(path, handler) }, read);
  let status, body;
  const res = { status(value) { status = value; return this; }, json(value) { body = value; return this; } };
  await routes.get('/scrape-mercados')({}, res);
  assert.equal(status, 502);
  assert.deepEqual(Object.keys(body), ['error', 'detalle']);
});

test('requires explicit configuration and rejects credential-bearing URLs', () => {
  assert.throws(() => createMarketClient({}), /configured/);
  assert.throws(() => createMarketClient({ baseUrl: 'https://user:secret@example.invalid', token: 'test' }),
    /without credentials/);
});
