package com.petroxpert.ms.services.market.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petroxpert.ms.services.market.MarketDataProvider;
import com.petroxpert.ms.services.market.MarketDefinition;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Temporary bridge to the working legacy collector. Only background acquisition performs HTTP. */
public final class LegacyMarketDataProvider implements MarketDataProvider {
    private final URI endpoint;
    private final Duration timeout;
    private final Duration refresh;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode batch;
    private long reusableUntil;
    private String batchError;

    public LegacyMarketDataProvider(String baseUrl, Duration timeout, Duration refresh) {
        if (baseUrl.isBlank()) { throw new IllegalArgumentException("LEGACY_NOT_CONFIGURED"); }
        var uri = URI.create(baseUrl);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("LEGACY_BASE_URL debe ser una URL HTTP sin credenciales");
        }
        endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/scrape-mercados");
        this.timeout = timeout;
        this.refresh = refresh;
        http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public BigDecimal fetch(MarketDefinition market) throws Exception { return fetchQuote(market).price(); }

    @Override
    public synchronized Quote fetchQuote(MarketDefinition market) throws IOException, InterruptedException {
        // All three collectors reuse one aggregate response, including an HTTP failure.
        if (System.nanoTime() >= reusableUntil) {
            batch = null;
            batchError = null;
            try {
                var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                        .header("Accept", "application/json").GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    batchError = "SOURCE_HTTP_" + response.statusCode();
                } else {
                    batch = json.readTree(response.body());
                    if (batch == null || !batch.isObject()) { batchError = "SOURCE_INVALID_DATA"; }
                }
            } catch (IOException error) {
                batchError = "SOURCE_CONNECTION_FAILED";
            } catch (InterruptedException error) {
                batchError = "SOURCE_INTERRUPTED";
                throw error;
            } finally {
                reusableUntil = System.nanoTime() + refresh.toNanos();
            }
        }
        if (batchError != null) { throw new IllegalArgumentException(batchError); }
        String field = switch (market) {
            case GASOIL -> "gasoil";
            case GASOLINE_RBOB -> "gasolina";
            case EUR_USD -> "tipoCambio";
        };
        var value = batch.path(field);
        var cached = batch.path("cached").path(field);
        var timestamp = batch.path("obtenidoEn").path(field);
        if (!value.isNumber() || !cached.isBoolean() || !timestamp.isTextual()) {
            throw new IllegalArgumentException("SOURCE_INVALID_DATA");
        }
        try {
            var price = new BigDecimal(value.asText());
            var fetchedAt = Instant.parse(timestamp.asText());
            if (price.signum() <= 0 || fetchedAt.isAfter(Instant.now().plusSeconds(5))) {
                throw new IllegalArgumentException("SOURCE_INVALID_DATA");
            }
            // cached=true is a normal 60-second legacy cache hit, not evidence of an acquisition failure.
            return new Quote(price, fetchedAt);
        } catch (java.time.format.DateTimeParseException | NumberFormatException error) {
            throw new IllegalArgumentException("SOURCE_INVALID_DATA", error);
        }
    }

    @Override public String source(MarketDefinition market) { return endpoint.toString(); }
    @Override public void close() { http.shutdownNow(); }
}
