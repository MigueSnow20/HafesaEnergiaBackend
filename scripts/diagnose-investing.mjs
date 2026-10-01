import { chromium } from 'playwright';

// Read-only comparison. Does not load database credentials or start the API.
const url = 'https://es.investing.com/commodities/london-gas-oil';
const waitMs = 20000;
const browser = await chromium.launch({ headless: true });
try {
  console.log(JSON.stringify({ browserVersion: browser.version() }));
  for (const mode of ['configured-user-agent', 'browser-default']) {
    const context = await browser.newContext({
      locale: 'es-ES',
      timezoneId: 'Europe/Madrid',
      viewport: { width: 1280, height: 720 },
      ...(mode === 'configured-user-agent' ? {
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36'
      } : {})
    });
    try {
      const page = await context.newPage();
      let lastDocument = null;
      page.on('response', response => {
        if (response.request().resourceType() === 'document' &&
            response.frame() === page.mainFrame()) {
          const headers = response.headers();
          lastDocument = {
            status: response.status(),
            challenge: headers['cf-mitigated'] ?? null
          };
        }
      });
      await page.route('**/*', route =>
        ['image', 'media', 'font'].includes(route.request().resourceType())
          ? route.abort() : route.continue());
      const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
      const headers = response ? await response.allHeaders() : {};
      const report = {
        mode, status: response?.status(), server: headers.server,
        challenge: headers['cf-mitigated'], title: await page.title(),
        userAgent: await page.evaluate(() => navigator.userAgent)
      };
      console.log(JSON.stringify({ phase: 'initial', ...report }));
      // Observe ordinary page navigation only, including after an initial 403.
      // No reload loop or interaction with the verification challenge.
      const startedAt = Date.now();
      const priceLocator = page.locator('[data-test="instrument-price-last"]').first();
      let price = null;
      try {
        await priceLocator.waitFor({ state: 'visible', timeout: waitMs });
        price = (await priceLocator.textContent({ timeout: 1000 }))?.trim() || null;
      } catch (error) {
        if (error.name !== 'TimeoutError') throw error;
      }
      console.log(JSON.stringify({
        phase: 'after-wait', mode,
        elapsedMs: Date.now() - startedAt,
        lastDocument,
        title: await page.title().catch(() => null),
        price,
        outcome: price ? 'price-found' : 'no-price-within-timeout'
      }));
    } catch (error) {
      console.log(JSON.stringify({ mode, error: error.message }));
      process.exitCode = 1;
    } finally {
      await context.close();
    }
  }
} finally {
  await browser.close();
}
