import { chromium } from 'playwright';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Run on Linux with: xvfb-run -a node diagnose-investing-headed.mjs
// Isolated diagnostic: no application credentials, database, or shared profile.
const url = 'https://es.investing.com/commodities/london-gas-oil';
const options = {
  headless: false,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  locale: 'es-ES',
  timezoneId: 'Europe/Madrid',
  viewport: { width: 1280, height: 720 },
  userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36'
};

async function observe(context, mode) {
  const page = await context.newPage();
  let lastDocument = null;
  page.on('response', response => {
    if (response.request().resourceType() === 'document' && response.frame() === page.mainFrame()) {
      lastDocument = {
        status: response.status(),
        challenge: response.headers()['cf-mitigated'] ?? null
      };
    }
  });
  await page.route('**/*', route =>
    ['image', 'media', 'font'].includes(route.request().resourceType())
      ? route.abort() : route.continue());
  // Report counts only; never print cookie contents.
  console.log(JSON.stringify({ mode, phase: 'start', cookieCount: (await context.cookies(url)).length }));
  try {
    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    console.log(JSON.stringify({ mode, phase: 'initial', status: response?.status(), lastDocument }));
    let price = null;
    const start = Date.now();
    const locator = page.locator('[data-test="instrument-price-last"]').first();
    try {
      await locator.waitFor({ state: 'visible', timeout: 20000 });
      price = (await locator.textContent({ timeout: 1000 }))?.trim() || null;
    } catch (error) {
      if (error.name !== 'TimeoutError') throw error;
    }
    console.log(JSON.stringify({
      mode, phase: 'result', elapsedMs: Date.now() - start,
      lastDocument, title: await page.title().catch(() => null), price,
      cookieCount: (await context.cookies(url)).length,
      outcome: price ? 'price-found' : 'no-price-within-timeout'
    }));
  } catch (error) {
    console.log(JSON.stringify({ mode, error: error.message }));
    process.exitCode = 1;
  } finally {
    await page.close();
  }
}

const profile = await mkdtemp(join(tmpdir(), 'investing-headed-diagnostic-'));
try {
  const { headless, args, ...contextOptions } = options;
  const browser = await chromium.launch({ headless, args });
  try {
    console.log(JSON.stringify({ browserVersion: browser.version(), headless }));
    const context = await browser.newContext(contextOptions);
    try {
      await observe(context, 'headed-fresh-context');
    } finally {
      await context.close();
    }
  } finally {
    await browser.close();
  }
  for (const mode of ['headed-persistent-first', 'headed-persistent-reopened']) {
    const context = await chromium.launchPersistentContext(profile, options);
    try {
      await observe(context, mode);
    } finally {
      await context.close();
    }
  }
} finally {
  // Only the uniquely created diagnostic profile is removed.
  await rm(profile, { recursive: true, force: true });
}
