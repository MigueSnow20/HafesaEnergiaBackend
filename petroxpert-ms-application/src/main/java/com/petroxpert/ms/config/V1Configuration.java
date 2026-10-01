package com.petroxpert.ms.config;

import com.petroxpert.ms.services.business.BusinessService;
import com.petroxpert.ms.services.business.BusinessStore;
import com.petroxpert.ms.services.business.LegacyHttpStore;
import com.petroxpert.ms.services.market.MarketCache;
import com.petroxpert.ms.services.market.MarketCollector;
import com.petroxpert.ms.services.market.MarketService;
import com.petroxpert.ms.services.market.MarketDataProvider;
import com.petroxpert.ms.services.market.MarketDefinition;
import com.petroxpert.ms.services.market.provider.HttpMarketDataProvider;
import com.petroxpert.ms.services.market.provider.LegacyMarketDataProvider;
import com.petroxpert.ms.services.market.provider.PlaywrightMarketDataProvider;
import java.time.Duration;
import java.util.EnumMap;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("v1")
public class V1Configuration {
    private Duration positive(Duration value) {
        if (value.isNegative() || value.toMillis() < 1) {
            throw new IllegalArgumentException("Las duraciones deben ser positivas");
        }
        return value;
    }

    @Bean
    MarketCache marketCache(@Value("${petroxpert.market.stale-after}") String staleAfter,
                            MarketDataProvider provider) {
        var sources = new EnumMap<MarketDefinition, String>(MarketDefinition.class);
        for (var market : MarketDefinition.values()) { sources.put(market, provider.source(market)); }
        return new MarketCache(positive(DurationStyle.detectAndParse(staleAfter)), sources);
    }

    @Bean(destroyMethod = "close")
    MarketDataProvider marketDataProvider(@Value("${petroxpert.market.provider}") String provider,
                                         @Value("${petroxpert.market.source-timeout}") String timeout,
                                         @Value("${legacy.base-url}") String legacyUrl,
                                         @Value("${petroxpert.market.refresh-interval}") String refresh) {
        Duration duration = positive(DurationStyle.detectAndParse(timeout));
        return switch (provider) {
            case "legacy" -> new LegacyMarketDataProvider(legacyUrl, duration,
                    positive(DurationStyle.detectAndParse(refresh)));
            case "playwright" -> new PlaywrightMarketDataProvider(duration);
            case "http" -> new HttpMarketDataProvider(duration);
            default -> throw new IllegalArgumentException("Proveedor de mercados desconocido: " + provider);
        };
    }

    @Bean(destroyMethod = "close")
    MarketCollector collector(MarketCache cache, MarketDataProvider provider,
                              @Value("${petroxpert.market.refresh-interval}") String refresh,
                              @Value("${petroxpert.market.max-backoff}") String backoff,
                              @Value("${petroxpert.market.enabled}") boolean enabled) {
        var collector = new MarketCollector(cache, positive(DurationStyle.detectAndParse(refresh)),
                positive(DurationStyle.detectAndParse(backoff)), provider);
        if (enabled) { collector.start(); }
        return collector;
    }

    @Bean(destroyMethod = "close")
    LegacyHttpStore businessStore(@Value("${legacy.base-url}") String url,
                                 @Value("${legacy.timeout}") Duration timeout) {
        return new LegacyHttpStore(url, positive(timeout));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    BusinessService businessService(BusinessStore store, @Value("${legacy.refresh}") Duration refresh,
                                    @Value("${legacy.stale-after}") Duration staleAfter,
                                    @Value("${legacy.max-backoff}") Duration backoff) {
        return new BusinessService(store, positive(refresh), positive(staleAfter), positive(backoff));
    }

    @Bean
    MarketService marketService(MarketCache cache, BusinessService business) {
        return new MarketService(cache, business);
    }

    @Bean
    WebMvcConfigurer cors(@Value("${web.allowed-origins}") String[] origins) {
        for (String origin : origins) {
            if (origin.contains("*")) { throw new IllegalArgumentException("CORS requiere orígenes explícitos"); }
        }
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/v1/**").allowedOrigins(origins)
                        .allowedMethods("GET", "POST").allowedHeaders("Content-Type")
                        .exposedHeaders("X-Market-Refresh-Ms");
            }
        };
    }
}
