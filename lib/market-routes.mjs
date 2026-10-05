export function installMarketRoutes(app, read) {
  const routes = [
    ['/scrape-gasoil', 'gasoil', 'Error al obtener datos de gasoil'],
    ['/scrape-gasolina', 'gasolina', 'Error al obtener datos de gasolina'],
    ['/scrape-tipo-cambio', 'tipoCambio', 'Error al obtener el tipo de cambio'],
    ['/scrape-mercados', null, 'Error al obtener los datos de mercado'],
  ];
  for (const [path, field, message] of routes) {
    app.get(path, async (req, res) => {
      try {
        const { data, cached } = await read();
        res.setHeader('Cache-Control', 'no-store');
        res.setHeader('X-Market-Source', 'IRONBEAM_WEB');
        res.setHeader('X-Market-Warning', 'MARKET_UNITS_UNVERIFIED, FX_FUTURE_NOT_SPOT');
        if (field) {
          const value = data[field];
          return res.json({ [field]: value.valor, textoOriginal: value.textoOriginal,
            cached, obtenidoEn: value.obtenidoEn });
        }
        return res.json({
          gasoil: data.gasoil.valor, gasolina: data.gasolina.valor, tipoCambio: data.tipoCambio.valor,
          cached: Object.fromEntries(Object.keys(data).map(key => [key, cached])),
          obtenidoEn: Object.fromEntries(Object.entries(data).map(([key, value]) => [key, value.obtenidoEn])),
        });
      } catch (error) {
        // Only fixed error codes; never log URLs, headers, credentials or upstream response bodies.
        console.error(`Market bridge ${path}: ${error.message}`);
        return res.status(502).json({ error: message, detalle: error.message });
      }
    });
  }
}
