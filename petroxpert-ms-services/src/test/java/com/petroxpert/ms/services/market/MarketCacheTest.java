package com.petroxpert.ms.services.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketCacheTest {
    private final MarketCache cache = new MarketCache(Duration.ofSeconds(45));
    private final Instant now = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void emptyAndExactStaleBoundary() {
        assertNull(cache.get(MarketDefinition.GASOIL, now).price());
        assertTrue(cache.get(MarketDefinition.GASOIL, now).stale());
        cache.success(MarketDefinition.GASOIL, new BigDecimal("712.50"), now, now.plusSeconds(15));
        assertFalse(cache.get(MarketDefinition.GASOIL, now.plusSeconds(44)).stale());
        assertTrue(cache.get(MarketDefinition.GASOIL, now.plusSeconds(45)).stale());
    }

    @Test
    void failurePreservesLastValueAndCaptureAndDoesNotAffectOtherMarkets() {
        cache.success(MarketDefinition.GASOIL, new BigDecimal("712.50"), now, now.plusSeconds(15));
        cache.success(MarketDefinition.EUR_USD, new BigDecimal("1.08"), now, now.plusSeconds(15));
        cache.failure(MarketDefinition.GASOIL, "SOURCE_HTTP_403", now.plusSeconds(30));
        cache.failure(MarketDefinition.GASOIL, "SOURCE_HTTP_403", now.plusSeconds(60));
        var result = cache.get(MarketDefinition.GASOIL, now.plusSeconds(50));
        assertEquals(new BigDecimal("712.50"), result.price());
        assertEquals(now, result.fetchedAt());
        assertEquals(2, result.consecutiveFailures());
        assertTrue(result.stale());
        assertEquals("ERROR", result.sourceStatus());
        assertEquals("OK", cache.get(MarketDefinition.EUR_USD, now).sourceStatus());
        cache.success(MarketDefinition.GASOIL, new BigDecimal("713"), now.plusSeconds(60), now.plusSeconds(75));
        assertEquals(0, cache.get(MarketDefinition.GASOIL, now.plusSeconds(60)).consecutiveFailures());
        assertNull(cache.get(MarketDefinition.GASOIL, now.plusSeconds(60)).error());
    }

    @Test
    void firstFailureDoesNotInventAValueOrTimestamp() {
        cache.failure(MarketDefinition.GASOIL, "SOURCE_HTTP_403", now.plusSeconds(15));
        var result = cache.get(MarketDefinition.GASOIL, now);
        assertNull(result.price());
        assertNull(result.fetchedAt());
        assertEquals("SOURCE_HTTP_403", result.error());
    }
}
