package com.petroxpert.ms.services.market;

import java.math.BigDecimal;
import java.time.Instant;

/** Acquisition port. Called only by background collectors; metadata must never perform I/O. */
public interface MarketDataProvider extends AutoCloseable {
    BigDecimal fetch(MarketDefinition market) throws Exception;

    record Quote(BigDecimal price, Instant fetchedAt) { }

    default Quote fetchQuote(MarketDefinition market) throws Exception {
        return new Quote(fetch(market), null);
    }

    String source(MarketDefinition market);

    @Override
    default void close() { }
}
