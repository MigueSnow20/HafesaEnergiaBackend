package com.petroxpert.ms.services.market.provider;

import com.petroxpert.ms.services.market.MarketDefinition;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyMarketDataProviderTest {
    private static final String RESPONSE = """
            {"gasoil":1468.25,"gasolina":3.2695,"tipoCambio":1.1333,
            "cached":{"gasoil":false,"gasolina":true,"tipoCambio":false},
            "obtenidoEn":{"gasoil":"2026-01-01T12:00:00Z","gasolina":"2026-01-01T11:59:00Z",
            "tipoCambio":"2026-01-01T12:00:01Z"}}
            """;

    @Test
    void sharesAggregateRequestAndPreservesOriginalDatesOnNormalCacheHit() throws Exception {
        var calls = new AtomicInteger();
        var server = server(RESPONSE, 200, calls);
        try (var provider = provider(server)) {
            var gasoil = provider.fetchQuote(MarketDefinition.GASOIL);
            var gasoline = provider.fetchQuote(MarketDefinition.GASOLINE_RBOB);
            var fx = provider.fetchQuote(MarketDefinition.EUR_USD);
            assertEquals(new BigDecimal("1468.25"), gasoil.price());
            assertEquals(new BigDecimal("3.2695"), gasoline.price());
            assertEquals(new BigDecimal("1.1333"), fx.price());
            assertEquals(Instant.parse("2026-01-01T11:59:00Z"), gasoline.fetchedAt());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void missingFieldDoesNotInvalidateOtherMarkets() throws Exception {
        var server = server(RESPONSE.replace("\"gasolina\":3.2695", "\"gasolina\":null"),
                200, new AtomicInteger());
        try (var provider = provider(server)) {
            assertThrows(IllegalArgumentException.class, () -> provider.fetchQuote(MarketDefinition.GASOLINE_RBOB));
            assertEquals(new BigDecimal("1.1333"), provider.fetchQuote(MarketDefinition.EUR_USD).price());
        } finally { server.stop(0); }
    }

    @Test
    void sharesHttpFailureInsteadOfIssuingThreeRequests() throws Exception {
        var calls = new AtomicInteger();
        var server = server("{}", 503, calls);
        try (var provider = provider(server)) {
            for (var market : MarketDefinition.values()) {
                assertEquals("SOURCE_HTTP_503", assertThrows(IllegalArgumentException.class,
                        () -> provider.fetchQuote(market)).getMessage());
            }
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsUnverifiableDatesAndNonNumericPrices() throws Exception {
        for (String body : new String[] {RESPONSE.replace("2026-01-01T12:00:00Z", "unknown"),
                RESPONSE.replace("\"gasoil\":1468.25", "\"gasoil\":\"1468.25\""),
                RESPONSE.replace("\"gasoil\":1468.25", "\"gasoil\":-1")}) {
            var server = server(body, 200, new AtomicInteger());
            try (var provider = provider(server)) {
                assertEquals("SOURCE_INVALID_DATA", assertThrows(IllegalArgumentException.class,
                        () -> provider.fetchQuote(MarketDefinition.GASOIL)).getMessage());
            } finally { server.stop(0); }
        }
    }

    private LegacyMarketDataProvider provider(HttpServer server) {
        return new LegacyMarketDataProvider("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2), Duration.ofSeconds(15));
    }

    private HttpServer server(String body, int status, AtomicInteger calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scrape-mercados", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }
}
