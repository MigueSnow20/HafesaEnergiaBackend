package com.petroxpert.ms.services.market.provider;

import com.petroxpert.ms.services.market.MarketDataProvider;
import com.petroxpert.ms.services.market.MarketDefinition;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Optional original transport, for environments where the source permits HTTP access. */
public final class HttpMarketDataProvider implements MarketDataProvider {
    private final HttpClient http;
    private final Duration timeout;

    public HttpMarketDataProvider(Duration timeout) {
        this.timeout = timeout;
        http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public BigDecimal fetch(MarketDefinition market) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(source(market))).timeout(timeout).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("SOURCE_HTTP_" + response.statusCode());
        }
        return InvestingParser.parseHtml(response.body());
    }

    @Override public String source(MarketDefinition market) { return InvestingSources.url(market); }
    @Override public void close() { http.shutdownNow(); }
}
