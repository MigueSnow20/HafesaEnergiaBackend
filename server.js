import express from 'express';
import axios from 'axios';
import cors from 'cors';
import * as cheerio from 'cheerio';
import pkg from 'pg';
const { Pool } = pkg;
import dotenv from 'dotenv';

// Cargar variables de entorno desde el archivo database.env
dotenv.config({ path: './database.env' });

const app = express();
const port = process.env.PORT || 3000;

// Configurar conexión a PostgreSQL
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: {
    rejectUnauthorized: false,
  },
});

// Middleware
app.use(cors());
app.use(express.json());

// Función para parsear números españoles
const parseSpanishNumber = (text) => {
  return parseFloat(
    text
      .trim()
      .replace(/\./g, '')
      .replace(',', '.')
  );
};

// Ruta para scraping del gasoil
app.get('/scrape-gasoil', async (req, res) => {
  try {
    const response = await axios.get(
      'https://es.investing.com/commodities/london-gas-oil',
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36',
        },
      }
    );

    const html = response.data;
    const $ = cheerio.load(html);

    const gasoilTexto = $('[data-test="instrument-price-last"]')
      .first()
      .text()
      .trim();

    const gasoil = parseSpanishNumber(gasoilTexto);

    console.log('========== SCRAPE GASOIL ==========');
    console.log('TEXTO:', gasoilTexto);
    console.log('PARSEADO:', gasoil);
    console.log('===================================');

    res.send({ gasoil });
  } catch (error) {
    console.error('❌ Error al obtener datos de gasoil:', error);
    res.status(500).send('Error al obtener datos de gasoil');
  }
});

// Ruta para scraping de la gasolina
app.get('/scrape-gasolina', async (req, res) => {
  try {
    const response = await axios.get(
      'https://es.investing.com/commodities/gasoline-rbob',
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36',
        },
      }
    );

    const html = response.data;
    const $ = cheerio.load(html);

    const gasolinaTexto = $('[data-test="instrument-price-last"]')
      .first()
      .text()
      .trim();

    const gasolina = parseSpanishNumber(gasolinaTexto);

    console.log('========== SCRAPE GASOLINA ==========');
    console.log('TEXTO:', gasolinaTexto);
    console.log('PARSEADO:', gasolina);
    console.log('=====================================');

    res.send({ gasolina });
  } catch (error) {
    console.error('❌ Error al obtener datos de gasolina:', error);
    res.status(500).send('Error al obtener datos de gasolina');
  }
});

// Ruta para scraping del tipo de cambio EUR/USD
app.get('/scrape-tipo-cambio', async (req, res) => {
  try {
    const response = await axios.get(
      'https://es.investing.com/currencies/eur-usd',
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36',
        },
      }
    );

    const html = response.data;
    const $ = cheerio.load(html);

    const tipoCambioTexto = $('[data-test="instrument-price-last"]')
      .first()
      .text()
      .trim();

    const tipoCambio = parseSpanishNumber(tipoCambioTexto);

    console.log('========== SCRAPE TIPO CAMBIO ==========');
    console.log('TEXTO:', tipoCambioTexto);
    console.log('PARSEADO:', tipoCambio);
    console.log('========================================');

    res.send({ tipoCambio });
  } catch (error) {
    console.error('❌ Error al obtener el tipo de cambio:', error);
    res.status(500).send('Error al obtener el tipo de cambio');
  }
});

// Crear tablas
const createTables = async () => {
  const client = await pool.connect();

  try {
    // Tabla cierre
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

    // Tabla informe
    await client.query(`
      CREATE TABLE IF NOT EXISTS informe (
        id SERIAL PRIMARY KEY,
        texto TEXT NOT NULL,
        fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
    `);

    // Tabla precios_ciudades
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
    console.error('❌ Error al crear las tablas:', error);
  } finally {
    client.release();
  }
};

// Crear tablas al iniciar
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

// Iniciar servidor
const PORT = process.env.PORT || 3000;

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Servidor corriendo en http://0.0.0.0:${PORT}`);
});