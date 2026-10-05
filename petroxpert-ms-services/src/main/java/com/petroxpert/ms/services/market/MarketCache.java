package com.petroxpert.ms.services.market;

import com.petroxpert.ms.contract.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketCache {
    private record State(BigDecimal price, Instant fetchedAt, int failures, Instant next, String error) { }
    private final ConcurrentHashMap<MarketDefinition, State> states = new ConcurrentHashMap<>();
    private final Duration staleAfter;
    private final Map<MarketDefinition, String> sources;

    public MarketCache(Duration staleAfter) {
        this(staleAfter, Map.of());
    }

    public MarketCache(Duration staleAfter, Map<MarketDefinition, String> sources) {
        this.staleAfter = staleAfter;
        this.sources = Map.copyOf(sources);
    }

    public void success(MarketDefinition market, BigDecimal price, Instant now, Instant next) {
        states.put(market, new State(price, now, 0, next, null));
    }

    public void failure(MarketDefinition market, String error, Instant next) {
        states.compute(market, (key, old) -> new State(old == null ? null : old.price,
                old == null ? null : old.fetchedAt, old == null ? 1 : old.failures + 1, next, error));
    }

    public MarketSnapshot get(MarketDefinition market, Instant now) {
        State state = states.get(market);
        boolean stale = state == null || state.fetchedAt == null
                || !now.isBefore(state.fetchedAt.plus(staleAfter));
        return new MarketSnapshot(market.name(), market.displayName, state == null ? null : state.price,
                market.unit, "USD", state == null ? null : state.fetchedAt, null, sources.get(market), stale,
                state == null ? "PENDING" : state.failures > 0 ? "ERROR" : "OK",
                state == null ? 0 : state.failures, state == null ? null : state.next,
                state == null ? "Esperando primera captura" : state.error);
    }

    public List<MarketSnapshot> all(Instant now) {
        return Arrays.stream(MarketDefinition.values()).map(market -> get(market, now)).toList();
    }
}
