import express from 'express';
import cors from 'cors';
import pkg from 'pg';
import { chromium } from 'playwright';
import dotenv from 'dotenv';

const { Pool } = pkg;

// Cargar variables desde database.env
dotenv.config({ path: './database.env' });

const app = express();
const PORT = process.env.PORT || 3000;

// PostgreSQL
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: {
    rejectUnauthorized: false
  }
});

// Middleware
app.use(cors());
app.use(express.json());

/**
 * Convierte números españoles:
 * 1.234,56 -> 1234.56
 * 712,50   -> 712.50
 */
const parseSpanishNumber = (text) => {
  if (!text || typeof text !== 'string') {
    return Number.NaN;
  }

  return Number.parseFloat(
    text
      .trim()
      .replace(/\./g, '')
      .replace(',', '.')
  );
};

/**
 * Configuración del scraping.
 */
const PRICE_SELECTOR = '[data-test="instrument-price-last"]';
const CACHE_DURATION_MS = 60_000;

// Una sola instancia de Chromium para toda la aplicación.
let browserInstance = null;
let browserLaunchPromise = null;

// Caché de resultados y peticiones en curso.
const priceCache = new Map();
const pendingScrapes = new Map();

/**
 * Devuelve una instancia compartida de Chromium.
 *
 * browserLaunchPromise evita que tres llamadas simultáneas
 * inicien tres navegadores durante el primer arranque.
 */
const getBrowser = async () => {
  if (browserInstance?.isConnected()) {
    return browserInstance;
  }

  if (!browserLaunchPromise) {
    console.log('🚀 Iniciando Chromium compartido...');

    browserLaunchPromise = chromium
      .launch({
        headless: true,
        args: [
          '--no-sandbox',
          '--disable-setuid-sandbox',
          '--disable-dev-shm-usage'
        ]
      })
      .then((browser) => {
        browserInstance = browser;

        browser.on('disconnected', () => {
          console.warn('⚠️ Chromium se ha desconectado');
          browserInstance = null;
        });

        return browser;
      })
      .finally(() => {
        browserLaunchPromise = null;
      });
  }

  return browserLaunchPromise;
};

/**
 * Obtiene un precio desde Investing.com.
 *
 * El navegador se mantiene abierto. Solo se cierra el contexto
 * utilizado por esta petición.
 */
const scrapeInvestingPrice = async ({ url, nombre }) => {
  const browser = await getBrowser();
  let context;

  const startedAt = Date.now();

  try {
    console.log(`🔄 Consultando ${nombre}: ${url}`);

    context = await browser.newContext({
      locale: 'es-ES',
      timezoneId: 'Europe/Madrid',
      viewport: {
        width: 1280,
        height: 720
      },
      userAgent:
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) ' +
        'AppleWebKit/537.36 (KHTML, like Gecko) ' +
        'Chrome/138.0.0.0 Safari/537.36'
    });

    const page = await context.newPage();

    // Evita descargar recursos pesados que no son necesarios
    // para localizar el precio.
    await page.route('**/*', async (route) => {
      const resourceType = route.request().resourceType();

      if (
        resourceType === 'image' ||
        resourceType === 'media' ||
        resourceType === 'font'
      ) {
        return route.abort();
      }

      return route.continue();
    });

const response = await page.goto(url, {
  waitUntil: 'commit',
  timeout: 60_000
});

console.log(await page.content());

    if (!response) {
      throw new Error(`No se recibió respuesta al abrir ${url}`);
    }

    if (response.status() >= 400) {
      throw new Error(
        `Investing.com respondió con HTTP ${response.status()}`
      );
    }

    const priceLocator = page.locator(PRICE_SELECTOR).first();

await priceLocator.waitFor({
  state: 'visible',
  timeout: 60_000
});

    const textoLimpio = (await priceLocator.textContent())?.trim();

    if (!textoLimpio) {
      throw new Error(
        `No se encontró el valor de ${nombre}`
      );
    }

    const valor = parseSpanishNumber(textoLimpio);

    if (!Number.isFinite(valor)) {
      throw new Error(
        `No se pudo convertir el valor "${textoLimpio}"`
      );
    }

    console.log(
      `✅ ${nombre}: ${valor} en ${Date.now() - startedAt} ms`
    );

    return {
      valor,
      textoOriginal: textoLimpio,
      obtenidoEn: new Date().toISOString()
    };
  } finally {
    if (context) {
      await context.close();
    }
  }
};

/**
 * Devuelve el precio desde caché o realiza el scraping.
 *
 * También reutiliza una petición que ya se encuentre en curso
 * para evitar dos scrapings simultáneos del mismo producto.
 */
const getPrice = async ({ key, url, nombre }) => {
  const cached = priceCache.get(key);

  if (
    cached &&
    Date.now() - cached.timestamp < CACHE_DURATION_MS
  ) {
    console.log(`⚡ Caché utilizada para ${nombre}`);

    return {
      ...cached.result,
      cached: true
    };
  }

  if (pendingScrapes.has(key)) {
    console.log(`⏳ Reutilizando petición en curso para ${nombre}`);
    return pendingScrapes.get(key);
  }

  const scrapingPromise = scrapeInvestingPrice({
    url,
    nombre
  })
    .then((result) => {
      priceCache.set(key, {
        result,
        timestamp: Date.now()
      });

      return {
        ...result,
        cached: false
      };
    })
    .finally(() => {
      pendingScrapes.delete(key);
    });

  pendingScrapes.set(key, scrapingPromise);

  return scrapingPromise;
};

// Configuración centralizada de mercados.
const MARKETS = {
  gasoil: {
    url: 'https://es.investing.com/commodities/london-gas-oil',
    nombre: 'gasoil'
  },
  gasolina: {
    url: 'https://es.investing.com/commodities/gasoline-rbob',
    nombre: 'gasolina'
  },
  tipoCambio: {
    url: 'https://es.investing.com/currencies/eur-usd',
    nombre: 'tipo de cambio'
  }
};

// Comprobación básica.
app.get('/', (req, res) => {
  res.json({
    status: 'ok',
    message: 'Backend Hafesa Energía funcionando'
  });
});

// Gasoil.
app.get('/scrape-gasoil', async (req, res) => {
  try {
    const resultado = await getPrice({
      key: 'gasoil',
      ...MARKETS.gasoil
    });

    return res.json({
      gasoil: resultado.valor,
      textoOriginal: resultado.textoOriginal,
      cached: resultado.cached,
      obtenidoEn: resultado.obtenidoEn
    });
  } catch (error) {
    console.error('❌ Error al obtener gasoil:', error);

    return res.status(502).json({
      error: 'Error al obtener datos de gasoil',
      detalle: error.message
    });
  }
});

// Gasolina.
app.get('/scrape-gasolina', async (req, res) => {
  try {
    const resultado = await getPrice({
      key: 'gasolina',
      ...MARKETS.gasolina
    });

    return res.json({
      gasolina: resultado.valor,
      textoOriginal: resultado.textoOriginal,
      cached: resultado.cached,
      obtenidoEn: resultado.obtenidoEn
    });
  } catch (error) {
    console.error('❌ Error al obtener gasolina:', error);

    return res.status(502).json({
      error: 'Error al obtener datos de gasolina',
      detalle: error.message
    });
  }
});

// Tipo de cambio EUR/USD.
app.get('/scrape-tipo-cambio', async (req, res) => {
  try {
    const resultado = await getPrice({
      key: 'tipoCambio',
      ...MARKETS.tipoCambio
    });

    return res.json({
      tipoCambio: resultado.valor,
      textoOriginal: resultado.textoOriginal,
      cached: resultado.cached,
      obtenidoEn: resultado.obtenidoEn
    });
  } catch (error) {
    console.error(
      '❌ Error al obtener tipo de cambio:',
      error
    );

    return res.status(502).json({
      error: 'Error al obtener el tipo de cambio',
      detalle: error.message
    });
  }
});

/**
 * Endpoint opcional para obtener los tres valores en una sola petición.
 */
app.get('/scrape-mercados', async (req, res) => {
  try {
    const [gasoil, gasolina, tipoCambio] =
      await Promise.all([
        getPrice({
          key: 'gasoil',
          ...MARKETS.gasoil
        }),
        getPrice({
          key: 'gasolina',
          ...MARKETS.gasolina
        }),
        getPrice({
          key: 'tipoCambio',
          ...MARKETS.tipoCambio
        })
      ]);

    return res.json({
      gasoil: gasoil.valor,
      gasolina: gasolina.valor,
      tipoCambio: tipoCambio.valor,
      cached: {
        gasoil: gasoil.cached,
        gasolina: gasolina.cached,
        tipoCambio: tipoCambio.cached
      },
      obtenidoEn: {
        gasoil: gasoil.obtenidoEn,
        gasolina: gasolina.obtenidoEn,
        tipoCambio: tipoCambio.obtenidoEn
      }
    });
  } catch (error) {
    console.error(
      '❌ Error al obtener los mercados:',
      error
    );

    return res.status(502).json({
      error: 'Error al obtener los datos de mercado',
      detalle: error.message
    });
  }
});
// Crear tablas
const createTables = async () => {
  let client;

  try {
    client = await pool.connect();

    await client.query(`
      CREATE TABLE IF NOT EXISTS cierre (
        id SERIAL PRIMARY KEY,
        ice NUMERIC(10,2) NOT NULL,
        deltaMed NUMERIC(10,2) NOT NULL,
        deltaNWE NUMERIC(10,2) NOT NULL,
        divisa NUMERIC(10,4) NOT NULL,
        gna NUMERIC(10,4) NOT NULL,
        gnaNWE NUMERIC(10,4) NOT NULL,
        gnaMED NUMERIC(10,4) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
    `);

    await client.query(`
      CREATE TABLE IF NOT EXISTS informe (
        id SERIAL PRIMARY KEY,
        texto TEXT NOT NULL,
        fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
    `);

    await client.query(`
      CREATE TABLE IF NOT EXISTS precios_ciudades (
        id SERIAL PRIMARY KEY,
        gasoilVigo NUMERIC(10,4) NOT NULL,
        gasolinaFirstVigo NUMERIC(10,4) NOT NULL,
        gasolinaSecondVigo NUMERIC(10,4) NOT NULL,
        gasoilHuelva NUMERIC(10,4) NOT NULL,
        gasolinaFirstHuelva NUMERIC(10,4) NOT NULL,
        gasolinaSecondHuelva NUMERIC(10,4) NOT NULL,
        gasoilMerida NUMERIC(10,4) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
    `);

    console.log('✅ Tablas creadas correctamente');
  } catch (error) {
    console.error(
      '❌ Error al conectar o crear las tablas:',
      error.message
    );
  } finally {
    if (client) {
      client.release();
    }
  }
};

createTables();

// Insertar datos en precios_ciudades
app.post('/insert-precios-ciudades', async (req, res) => {
  const {
    gasoilVigo,
    gasolinaFirstVigo,
    gasolinaSecondVigo,
    gasoilHuelva,
    gasolinaFirstHuelva,
    gasolinaSecondHuelva,
    gasoilMerida,
  } = req.body;

  if (
    [
      gasoilVigo,
      gasolinaFirstVigo,
      gasolinaSecondVigo,
      gasoilHuelva,
      gasolinaFirstHuelva,
      gasolinaSecondHuelva,
      gasoilMerida,
    ].some((value) => value === null || value === undefined)
  ) {
    return res.status(400).json({
      error: 'Todos los valores son obligatorios y no pueden ser nulos',
    });
  }

  const client = await pool.connect();

  try {
    await client.query(
      `
      INSERT INTO precios_ciudades (
        gasoilVigo,
        gasolinaFirstVigo,
        gasolinaSecondVigo,
        gasoilHuelva,
        gasolinaFirstHuelva,
        gasolinaSecondHuelva,
        gasoilMerida
      ) 
      VALUES ($1, $2, $3, $4, $5, $6, $7);
    `,
      [
        gasoilVigo,
        gasolinaFirstVigo,
        gasolinaSecondVigo,
        gasoilHuelva,
        gasolinaFirstHuelva,
        gasolinaSecondHuelva,
        gasoilMerida,
      ]
    );

    res.json({
      message: '✅ Datos insertados en la tabla precios_ciudades',
    });
  } catch (error) {
    console.error(
      '❌ Error al insertar datos en precios_ciudades:',
      error
    );

    res.status(500).json({
      error: 'Error al insertar datos en precios_ciudades',
    });
  } finally {
    client.release();
  }
});

// Obtener último registro de precios_ciudades
app.get('/precios-ciudades-ultimo', async (req, res) => {
  const client = await pool.connect();

  try {
    const result = await client.query(`
      SELECT * FROM precios_ciudades
      ORDER BY created_at DESC
      LIMIT 1;
    `);

    res.json(
      result.rows[0] || {
        message: 'No hay datos en la tabla precios_ciudades',
      }
    );
  } catch (error) {
    console.error(
      '❌ Error al obtener precios_ciudades:',
      error
    );

    res.status(500).json({
      error: 'Error al obtener precios_ciudades',
    });
  } finally {
    client.release();
  }
});

// Insertar datos en cierre
app.post('/insert-cierre', async (req, res) => {
  const {
    ice,
    deltaMed,
    deltaNWE,
    divisa,
    gna,
    gnaNWE,
    gnaMED,
  } = req.body;

  if (
    [
      ice,
      deltaMed,
      deltaNWE,
      divisa,
      gna,
      gnaNWE,
      gnaMED,
    ].some((value) => value === null || value === undefined)
  ) {
    return res.status(400).json({
      error: 'Todos los valores son obligatorios y no pueden ser nulos',
    });
  }

  const client = await pool.connect();

  try {
    await client.query(
      `
      INSERT INTO cierre (
        ice,
        deltaMed,
        deltaNWE,
        divisa,
        gna,
        gnaNWE,
        gnaMED
      )
      VALUES ($1, $2, $3, $4, $5, $6, $7);
    `,
      [
        ice,
        deltaMed,
        deltaNWE,
        divisa,
        gna,
        gnaNWE,
        gnaMED,
      ]
    );

    res.json({
      message: '✅ Datos insertados en cierre',
    });
  } catch (error) {
    console.error(
      '❌ Error al insertar datos en cierre:',
      error
    );

    res.status(500).json({
      error: 'Error al insertar datos en cierre',
    });
  } finally {
    client.release();
  }
});

// Insertar informe
app.post('/insert-informe', async (req, res) => {
  const { texto } = req.body;

  if (!texto || typeof texto !== 'string' || texto.trim() === '') {
    return res.status(400).json({
      error:
        'El campo texto es obligatorio y debe ser una cadena no vacía',
    });
  }

  const client = await pool.connect();

  try {
    await client.query(
      `
      INSERT INTO informe (texto)
      VALUES ($1);
    `,
      [texto]
    );

    res.json({
      message: '✅ Informe insertado correctamente',
    });
  } catch (error) {
    console.error('❌ Error al insertar informe:', error);

    res.status(500).json({
      error: 'Error al insertar informe',
    });
  } finally {
    client.release();
  }
});

// Obtener último cierre
app.get('/cierre-ultimo', async (req, res) => {
  const client = await pool.connect();

  try {
    const result = await client.query(`
      SELECT * FROM cierre
      ORDER BY created_at DESC
      LIMIT 1;
    `);

    res.json(
      result.rows[0] || {
        message: 'No hay datos en la tabla cierre',
      }
    );
  } catch (error) {
    console.error(
      '❌ Error al obtener el último cierre:',
      error
    );

    res.status(500).json({
      error: 'Error al obtener datos de cierre',
    });
  } finally {
    client.release();
  }
});

// Obtener informes
app.get('/informes', async (req, res) => {
  const client = await pool.connect();

  try {
    const result = await client.query(`
      SELECT * FROM informe
      ORDER BY fecha DESC;
    `);

    res.json(result.rows);
  } catch (error) {
    console.error('❌ Error al obtener informes:', error);

    res.status(500).json({
      error: 'Error al obtener informes',
    });
  } finally {
    client.release();
  }
});


const closeBrowser = async () => {
  if (browserInstance?.isConnected()) {
    console.log('🛑 Cerrando Chromium...');
    await browserInstance.close();
  }

  browserInstance = null;
};

process.on('SIGTERM', async () => {
  await closeBrowser();
  await pool.end();
  process.exit(0);
});

process.on('SIGINT', async () => {
  await closeBrowser();
  await pool.end();
  process.exit(0);
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(
    `🚀 Servidor corriendo en http://0.0.0.0:${PORT}`
  );
});