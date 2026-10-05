import express from 'express';
import cors from 'cors';
import pkg from 'pg';
import { createMarketClient } from './lib/market-client.mjs';
import { installMarketRoutes } from './lib/market-routes.mjs';
import dotenv from 'dotenv';
import { createV1Proxy } from './lib/v1-proxy.mjs';

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
const allowedOrigins = (process.env.LEGACY_ALLOWED_ORIGINS ?? '').split(',').map(value => value.trim()).filter(Boolean);
if (allowedOrigins.includes('*')) throw new Error('LEGACY_ALLOWED_ORIGINS requires explicit origins');
app.use(cors({ origin: allowedOrigins, exposedHeaders: ['X-Market-Refresh-Ms'] }));
app.use('/api/v1', createV1Proxy(process.env.SPRING_INTERNAL_URL));
app.use(express.json());

const readMarkets = createMarketClient({
  baseUrl: process.env.PETROXPERT_MARKET_API_URL,
  token: process.env.PETROXPERT_MARKET_API_TOKEN,
  timeoutMs: Number(process.env.PETROXPERT_MARKET_API_TIMEOUT_MS ?? 5000),
});
installMarketRoutes(app, readMarkets);

app.get('/', (req, res) => {
  res.json({ status: 'ok', message: 'Backend Hafesa Energia funcionando' });
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


process.on('SIGTERM', async () => {
  await pool.end();
  process.exit(0);
});

process.on('SIGINT', async () => {
  await pool.end();
  process.exit(0);
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(
    `🚀 Servidor corriendo en http://0.0.0.0:${PORT}`
  );
});
