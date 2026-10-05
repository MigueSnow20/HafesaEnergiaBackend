package com.petroxpert.ms.contract;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketSnapshot(
        String code, String name, BigDecimal price, String unit, String currency,
        Instant fetchedAt, Instant observedAt, String source, boolean stale,
        String sourceStatus, int consecutiveFailures, Instant nextAttemptAt, String error) {
}
