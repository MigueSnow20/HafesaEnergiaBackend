package com.petroxpert.ms.services.market;

import java.io.IOException;
import java.time.Duration;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MarketCollector implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MarketCollector.class);
    // Acquisition is serialized across markets; each next run waits until the previous run has finished.
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final MarketDataProvider provider;
    private final MarketCache cache;
    private final Duration refresh;
    private final Duration maxBackoff;
    private final Clock clock;

    public MarketCollector(MarketCache cache, Duration refresh, Duration maxBackoff, MarketDataProvider provider) {
        this(cache, refresh, maxBackoff, provider, Clock.systemUTC());
    }

    MarketCollector(MarketCache cache, Duration refresh, Duration maxBackoff,
                    MarketDataProvider provider, Clock clock) {
        this.cache = cache;
        this.refresh = refresh;
        this.maxBackoff = maxBackoff;
        this.provider = provider;
        this.clock = clock;
    }

    public void start() {
        for (var market : MarketDefinition.values()) {
            executor.scheduleWithFixedDelay(() -> collect(market), 0, refresh.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void collect(MarketDefinition market) {
        var previous = cache.get(market, clock.instant());
        if (previous.nextAttemptAt() != null && clock.instant().isBefore(previous.nextAttemptAt())) {
            return;
        }
        try {
            var quote = provider.fetchQuote(market);
            var price = java.util.Objects.requireNonNull(quote.price(), "Provider returned no price");
            var now = clock.instant();
            var fetchedAt = quote.fetchedAt() == null ? now : quote.fetchedAt();
            cache.success(market, price, fetchedAt, now.plus(refresh));
            LOG.info("event=market_updated market={} fetchedAt={}", market, fetchedAt);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOG.info("event=collector_interrupted market={}", market);
        } catch (IOException | IllegalArgumentException error) {
            failed(market, error instanceof IOException ? "SOURCE_CONNECTION_FAILED" : error.getMessage());
        } catch (Exception error) {
            // Scheduled-task boundary: a provider defect must not silently cancel all future refreshes.
            LOG.error("event=collector_unexpected market={} type={}", market, error.getClass().getSimpleName());
            failed(market, "SOURCE_UNEXPECTED_ERROR");
        }
    }

    private void failed(MarketDefinition market, String reason) {
        int failures = cache.get(market, clock.instant()).consecutiveFailures();
        long delay = Math.min(maxBackoff.toMillis(), refresh.toMillis() * (1L << Math.min(failures, 10)));
        cache.failure(market, reason, clock.instant().plusMillis(delay));
        LOG.warn("event=market_refresh_failed market={} reason={} retryAfterMs={}", market, reason, delay);
    }

    @Override
    public void close() {
        // Stop scheduling first; let an in-flight browser operation finish before the provider bean is closed.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) { executor.shutdownNow(); }
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
