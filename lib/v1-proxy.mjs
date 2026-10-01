import http from 'node:http';

/** Forwards V1 to internal Spring before Express parses the request body. */
export function createV1Proxy(origin = 'http://127.0.0.1:8080') {
  const target = new URL(origin);
  if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' || target.pathname !== '/') {
    throw new Error('SPRING_INTERNAL_URL must point to the local Spring service');
  }
  return (req, res) => {
    const headers = { ...req.headers, host: target.host };
    // Express handles public CORS as before; Spring is internal to this deployment.
    delete headers.origin;
    delete headers.connection;
    const upstream = http.request({
      hostname: target.hostname, port: target.port, method: req.method,
      path: req.originalUrl ?? req.url, headers, timeout: 20000,
    }, response => {
      res.statusCode = response.statusCode ?? 502;
      for (const [name, value] of Object.entries(response.headers)) {
        if (value !== undefined && name !== 'connection' && !name.startsWith('access-control-')) {
          res.setHeader(name, value);
        }
      }
      response.on('error', () => res.destroy());
      response.pipe(res);
    });
    upstream.on('timeout', () => upstream.destroy(new Error('Internal Spring timeout')));
    upstream.on('error', () => {
      if (res.headersSent) return res.destroy();
      res.statusCode = 503;
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ detail: 'Servicio temporalmente no disponible' }));
    });
    req.on('aborted', () => upstream.destroy());
    res.on('close', () => { if (!res.writableFinished) upstream.destroy(); });
    req.pipe(upstream);
  };
}
