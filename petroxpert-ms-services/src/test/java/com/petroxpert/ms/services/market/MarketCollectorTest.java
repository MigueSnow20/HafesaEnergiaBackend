package com.petroxpert.ms.services.market;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketCollectorTest {
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blockedSourceBacksOffIndependentlyAndRecovers() throws Exception {
        var clock = new TestClock();
        var cache = new MarketCache(Duration.ofSeconds(45));
        var provider = mock(MarketDataProvider.class, CALLS_REAL_METHODS);
        when(provider.fetch(any())).thenThrow(new IllegalArgumentException("SOURCE_HTTP_403"))
                .thenReturn(new BigDecimal("1.1234"))
                .thenThrow(new IllegalArgumentException("SOURCE_HTTP_403")).thenReturn(new BigDecimal("712.50"));
        try (var collector = new MarketCollector(cache, Duration.ofSeconds(15),
                Duration.ofMinutes(5), provider, clock)) {
            collector.collect(MarketDefinition.GASOIL);
            assertEquals(clock.now.plusSeconds(15), cache.get(MarketDefinition.GASOIL, clock.now).nextAttemptAt());
            collector.collect(MarketDefinition.GASOIL);
            verify(provider, times(1)).fetch(any());
            collector.collect(MarketDefinition.EUR_USD);
            assertEquals("OK", cache.get(MarketDefinition.EUR_USD, clock.now).sourceStatus());
            clock.now = clock.now.plusSeconds(15);
            collector.collect(MarketDefinition.GASOIL);
            assertEquals(clock.now.plusSeconds(30), cache.get(MarketDefinition.GASOIL, clock.now).nextAttemptAt());
            clock.now = clock.now.plusSeconds(30);
            collector.collect(MarketDefinition.GASOIL);
            assertEquals(0, cache.get(MarketDefinition.GASOIL, clock.now).consecutiveFailures());
            assertEquals(clock.now, cache.get(MarketDefinition.GASOIL, clock.now).fetchedAt());
        }
    }

    @Test
    void providerFailurePreservesSnapshotUntilStaleThenRecovers() throws Exception {
        var clock = new TestClock();
        var cache = new MarketCache(Duration.ofSeconds(45));
        var provider = mock(MarketDataProvider.class, CALLS_REAL_METHODS);
        when(provider.fetch(any())).thenReturn(new BigDecimal("712.50"))
                .thenThrow(new IllegalArgumentException("SOURCE_TIMEOUT"))
                .thenThrow(new IllegalArgumentException("SOURCE_TIMEOUT"))
                .thenReturn(new BigDecimal("713.00"));
        try (var collector = new MarketCollector(cache, Duration.ofSeconds(15),
                Duration.ofMinutes(5), provider, clock)) {
            collector.collect(MarketDefinition.GASOIL);
            var good = cache.get(MarketDefinition.GASOIL, clock.now);
            clock.now = clock.now.plusSeconds(15);
            collector.collect(MarketDefinition.GASOIL);
            assertEquals(good.price(), cache.get(MarketDefinition.GASOIL, clock.now).price());
            assertEquals(good.fetchedAt(), cache.get(MarketDefinition.GASOIL, clock.now).fetchedAt());
            assertFalse(cache.get(MarketDefinition.GASOIL, clock.now).stale());
            clock.now = clock.now.plusSeconds(30);
            collector.collect(MarketDefinition.GASOIL);
            assertTrue(cache.get(MarketDefinition.GASOIL, clock.now).stale());
            clock.now = clock.now.plusSeconds(30);
            collector.collect(MarketDefinition.GASOIL);
            assertEquals("OK", cache.get(MarketDefinition.GASOIL, clock.now).sourceStatus());
            assertEquals(clock.now, cache.get(MarketDefinition.GASOIL, clock.now).fetchedAt());
        }
    }

    @Test
    void cachedQuoteKeepsOriginalAgeUntilFreshCapture() throws Exception {
        var clock = new TestClock();
        var cache = new MarketCache(Duration.ofSeconds(45));
        var provider = mock(MarketDataProvider.class);
        var original = clock.now.minusSeconds(60);
        when(provider.fetchQuote(any())).thenReturn(
                new MarketDataProvider.Quote(new BigDecimal("712.50"), original),
                new MarketDataProvider.Quote(new BigDecimal("713.00"), clock.now.plusSeconds(15)));
        try (var collector = new MarketCollector(cache, Duration.ofSeconds(15),
                Duration.ofMinutes(5), provider, clock)) {
            collector.collect(MarketDefinition.GASOIL);
            var cached = cache.get(MarketDefinition.GASOIL, clock.now);
            assertEquals(original, cached.fetchedAt());
            assertEquals("OK", cached.sourceStatus());
            assertNull(cached.error());
            assertTrue(cached.stale());
            clock.now = clock.now.plusSeconds(15);
            collector.collect(MarketDefinition.GASOIL);
            var recovered = cache.get(MarketDefinition.GASOIL, clock.now);
            assertEquals("OK", recovered.sourceStatus());
            assertFalse(recovered.stale());
            assertEquals(clock.now, recovered.fetchedAt());
        }
    }

    @Test
    void thirtySecondRefreshPreservesFailureBackoff() throws Exception {
        var clock = new TestClock();
        var cache = new MarketCache(Duration.ofSeconds(90));
        var provider = mock(MarketDataProvider.class, CALLS_REAL_METHODS);
        when(provider.fetch(any())).thenThrow(new IllegalArgumentException("SOURCE_HTTP_429"));
        try (var collector = new MarketCollector(cache, Duration.ofSeconds(30),
                Duration.ofMinutes(5), provider, clock)) {
            collector.collect(MarketDefinition.GASOIL);
            assertEquals(clock.now.plusSeconds(30), cache.get(MarketDefinition.GASOIL, clock.now).nextAttemptAt());
            clock.now = clock.now.plusSeconds(29);
            collector.collect(MarketDefinition.GASOIL);
            verify(provider, times(1)).fetch(any());
            clock.now = clock.now.plusSeconds(1);
            collector.collect(MarketDefinition.GASOIL);
            assertEquals(clock.now.plusSeconds(60), cache.get(MarketDefinition.GASOIL, clock.now).nextAttemptAt());
        }
    }

    @Test
    void slowAcquisitionDoesNotOverlapOtherMarkets() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(3);
        var unexpectedOverlap = new java.util.concurrent.CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var provider = mock(MarketDataProvider.class, CALLS_REAL_METHODS);
        when(provider.fetch(any())).thenAnswer(call -> {
            if (active.incrementAndGet() > 1) unexpectedOverlap.countDown();
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return BigDecimal.ONE;
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
        });
        try (var collector = new MarketCollector(new MarketCache(Duration.ofSeconds(90)),
                Duration.ofSeconds(30), Duration.ofMinutes(5), provider)) {
            try {
                collector.start();
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(unexpectedOverlap.await(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, unexpectedOverlap.getCount());
        }
    }

    @Test
    void warmupDoesNotWaitForRefreshInterval() throws Exception {
        var first = new java.util.concurrent.CountDownLatch(3);
        var provider = mock(MarketDataProvider.class, CALLS_REAL_METHODS);
        when(provider.fetch(any())).thenAnswer(call -> { first.countDown(); return BigDecimal.ONE; });
        try (var collector = new MarketCollector(new MarketCache(Duration.ofSeconds(45)),
                Duration.ofHours(1), Duration.ofHours(1), provider)) {
            collector.start();
            assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
