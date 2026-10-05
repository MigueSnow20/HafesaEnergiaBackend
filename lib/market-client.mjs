const mapping = {
  gasoil: ['GASOIL', 'G.V26'],
  gasolina: ['GASOLINE_RBOB', 'RB.X26'],
  tipoCambio: ['EUR_USD', 'M6E.Z26'],
};

/** One short-lived aggregate read shared by the legacy routes. Spring owns freshness and acquisition. */
export function createMarketClient({ baseUrl, token, timeoutMs = 5000, fetchImpl = fetch }) {
  if (!baseUrl || !token?.trim()) throw new Error('Market API URL and token must be configured');
  const origin = new URL(baseUrl);
  if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password
      || origin.pathname !== '/' || origin.search || origin.hash) {
    throw new Error('PETROXPERT_MARKET_API_URL must be an HTTP(S) origin without credentials');
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('Market API timeout must be positive');
  const endpoint = new URL('/internal/legacy-markets', origin);
  let batch;
  let reusableUntil = 0;
  let pending;

  async function load() {
    let response;
    try {
      response = await fetchImpl(endpoint, {
        headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(timeoutMs), redirect: 'error',
      });
    } catch {
      throw new Error('MARKET_API_UNAVAILABLE');
    }
    if (!response.ok) throw new Error(`MARKET_API_HTTP_${response.status}`);
    const payload = await response.json().catch(() => null);
    if (!Array.isArray(payload)) throw new Error('MARKET_API_INVALID_PAYLOAD');
    const result = {};
    for (const [field, [code, symbol]] of Object.entries(mapping)) {
      const matches = payload.filter(item => item?.code === code);
      const item = matches[0];
      if (matches.length !== 1 || item.symbol !== symbol || item.source !== 'IRONBEAM_WEB'
          || item.priceField !== 'LAST' || typeof item.price !== 'number'
          || !Number.isFinite(item.price) || item.price <= 0 || typeof item.fetchedAt !== 'string'
          || !Number.isFinite(Date.parse(item.fetchedAt)) || Date.parse(item.fetchedAt) > Date.now() + 5000
          || typeof item.stale !== 'boolean' || !['OK', 'ERROR'].includes(item.sourceStatus)) {
        throw new Error('MARKET_API_INVALID_PAYLOAD');
      }
      // The old single-price contract has no stale/error flag: fail rather than disguise a degraded capture.
      if (item.stale || item.sourceStatus !== 'OK') throw new Error('MARKET_API_STALE_OR_ERROR');
      result[field] = { valor: item.price, textoOriginal: String(item.price), obtenidoEn: item.fetchedAt };
    }
    batch = result;
    reusableUntil = Date.now() + 1000;
    return result;
  }

  return async function read() {
    if (batch && Date.now() < reusableUntil) return { data: batch, cached: true };
    if (pending) return pending;
    pending = load().then(data => ({ data, cached: false })).finally(() => { pending = undefined; });
    return pending;
  };
}
