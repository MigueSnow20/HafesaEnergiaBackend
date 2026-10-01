package com.petroxpert.ms.services.market.provider;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.petroxpert.ms.services.market.MarketDataProvider;
import com.petroxpert.ms.services.market.MarketDefinition;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Temporary visible-page adapter. All Playwright operations, including shutdown, share the same monitor. */
public final class PlaywrightMarketDataProvider implements MarketDataProvider {
    private static final Logger LOG = LoggerFactory.getLogger(PlaywrightMarketDataProvider.class);
    private final double timeoutMs;
    private final Supplier<Playwright> factory;
    private Playwright playwright;
    private Browser browser;
    private boolean closed;

    public PlaywrightMarketDataProvider(Duration timeout) {
        this(timeout, () -> Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"))));
    }

    PlaywrightMarketDataProvider(Duration timeout, Supplier<Playwright> factory) {
        timeoutMs = timeout.toMillis();
        this.factory = factory;
    }

    @Override
    public synchronized BigDecimal fetch(MarketDefinition market) {
        if (closed) { throw new IllegalStateException("SOURCE_CLOSED"); }
        long started = System.nanoTime();
        LOG.info("event=market_acquisition_started provider=playwright market={}", market);
        try {
            // Lazy creation keeps network/browser failures outside Spring startup.
            if (playwright == null) {
                playwright = factory.get();
            }
            if (browser == null || !browser.isConnected()) {
                if (browser != null) { browser.close(); }
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setTimeout(timeoutMs));
                LOG.info("event=market_browser_started provider=playwright");
            }
            // One short-lived context/page per acquisition; browser reused across all markets and refreshes.
            try (var context = browser.newContext()) {
                var page = context.newPage();
                long deadline = System.nanoTime() + (long) (timeoutMs * 1_000_000);
                var response = page.navigate(source(market), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.COMMIT).setTimeout(remaining(deadline)));
                if (response == null) { throw new IllegalArgumentException("SOURCE_NO_RESPONSE"); }
                if (response.status() >= 400) {
                    throw new IllegalArgumentException("SOURCE_HTTP_" + response.status());
                }
                var price = page.locator(InvestingSources.PRICE_SELECTOR).first();
                price.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(remaining(deadline)));
                String text = price.innerText(new Locator.InnerTextOptions().setTimeout(remaining(deadline)));
                return InvestingParser.parseSpanishNumber(text);
            }
        } catch (TimeoutError error) {
            throw new IllegalArgumentException("SOURCE_TIMEOUT");
        } catch (PlaywrightException error) {
            // Never publish Playwright call logs, page HTML, cookies or browser profiles to REST/logs.
            throw new IllegalArgumentException("SOURCE_BROWSER_UNAVAILABLE");
        } finally {
            LOG.info("event=market_acquisition_finished provider=playwright market={} durationMs={}",
                    market, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private double remaining(long deadline) {
        double milliseconds = (deadline - System.nanoTime()) / 1_000_000.0;
        if (milliseconds <= 0) { throw new IllegalArgumentException("SOURCE_TIMEOUT"); }
        return milliseconds;
    }

    @Override public String source(MarketDefinition market) { return InvestingSources.url(market); }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        closed = true;
        try {
            if (browser != null) { browser.close(); }
        } finally {
            if (playwright != null) { playwright.close(); }
            LOG.info("event=market_browser_closed provider=playwright");
        }
    }
}
