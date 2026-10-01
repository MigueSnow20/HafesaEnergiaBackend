package com.petroxpert.ms.services.market.provider;

import com.petroxpert.ms.services.market.MarketDefinition;

final class InvestingSources {
    static final String PRICE_SELECTOR = "[data-test=instrument-price-last]";

    private InvestingSources() { }

    static String url(MarketDefinition market) {
        return "https://es.investing.com/" + switch (market) {
            case GASOIL -> "commodities/london-gas-oil";
            case GASOLINE_RBOB -> "commodities/gasoline-rbob";
            case EUR_USD -> "currencies/eur-usd";
        };
    }
}
