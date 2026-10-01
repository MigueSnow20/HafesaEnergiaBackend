package com.petroxpert.ms.services.business;

import com.petroxpert.ms.contract.BusinessDtos.ReportInput;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyHttpStoreTest {
    @Test
    void missingConfigurationIsDegradedWithoutNetwork() {
        try (var client = new LegacyHttpStore("", Duration.ofSeconds(1))) {
            assertEquals("LEGACY_NOT_CONFIGURED",
                    assertThrows(StoreUnavailableException.class, client::latestClosing).getMessage());
        }
    }

    @Test
    void mapsLegacyFieldsAndWritesReportWithoutInventingDates() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var posted = new AtomicReference<String>();
        server.createContext("/cierre-ultimo", exchange -> {
            String body = """
                    {"id":3,"created_at":"2026-01-01T12:00:00Z","ice":"700.00","deltamed":"20.00",
                    "deltanwe":"10.00","divisa":"1.1000","gna":"2.0000","gnanwe":"650","gnamed":"660"}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/insert-informe", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var client = new LegacyHttpStore("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2))) {
            var closing = client.latestClosing();
            assertEquals("3", closing.id());
            assertEquals("20.00", closing.values().deltaMed().toPlainString());
            client.saveReport(new ReportInput("Informe de prueba"));
            assertEquals("{\"texto\":\"Informe de prueba\"}", posted.get());
        } finally { server.stop(0); }
    }
}
