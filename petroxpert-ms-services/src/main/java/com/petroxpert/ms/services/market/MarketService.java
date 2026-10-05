package com.petroxpert.ms.services.market;

import com.petroxpert.ms.contract.MarketSnapshot;
import com.petroxpert.ms.contract.BusinessDtos.*;
import com.petroxpert.ms.services.business.BusinessService;
import com.petroxpert.ms.services.business.LegacyCalculations;
import java.time.Instant;
import java.util.List;

public final class MarketService {
    private final MarketCache cache;
    private final BusinessService business;

    public MarketService(MarketCache cache, BusinessService business) {
        this.cache = cache;
        this.business = business;
    }

    public List<MarketSnapshot> all() { return cache.all(Instant.now()); }
    public MarketSnapshot get(MarketDefinition market) { return cache.get(market, Instant.now()); }

    public Summary summary() {
        var markets = all();
        var closing = business.closing();
        var premiums = business.premiums();
        var reports = business.reports();
        boolean stale = markets.stream().anyMatch(m -> m.stale() || !"OK".equals(m.sourceStatus()))
                || !"OK".equals(closing.status()) || !"OK".equals(premiums.status());
        Variations variations = null;
        List<CityPrice> cities = List.of();
        String status = "MISSING_INPUTS";
        if (closing.data() != null && markets.stream().allMatch(m -> m.price() != null)) {
            try {
                variations = LegacyCalculations.variations(markets.get(0).price(), markets.get(1).price(),
                        markets.get(2).price(), closing.data().values());
                if (premiums.data() != null) {
                    cities = LegacyCalculations.cities(variations, closing.data().values(), premiums.data().values());
                }
                status = stale ? "STALE_INPUTS" : "LEGACY_COMPATIBILITY";
            } catch (IllegalArgumentException error) {
                status = error.getMessage();
            }
        }
        return new Summary(markets, closing, premiums, reports, variations, cities, stale, status);
    }
}
