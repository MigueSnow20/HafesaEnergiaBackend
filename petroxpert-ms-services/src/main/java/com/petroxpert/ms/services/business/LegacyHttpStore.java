package com.petroxpert.ms.services.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petroxpert.ms.contract.BusinessDtos.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LegacyHttpStore implements BusinessStore, AutoCloseable {
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public LegacyHttpStore(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        if (!baseUrl.isBlank()) {
            var uri = URI.create(baseUrl);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("LEGACY_BASE_URL debe ser una URL HTTP sin credenciales");
            }
        }
        http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private JsonNode exchange(String path, Object body) {
        if (baseUrl.isBlank()) {
            throw new StoreUnavailableException("LEGACY_NOT_CONFIGURED");
        }
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                    .header("Accept", "application/json");
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new StoreUnavailableException("LEGACY_HTTP_" + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("LEGACY_INTERRUPTED", error);
        } catch (IOException error) {
            throw new StoreUnavailableException(body == null ? "LEGACY_UNAVAILABLE"
                    : "LEGACY_WRITE_UNCONFIRMED: compruebe los datos antes de repetir", error);
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        try {
            return new BigDecimal(node.path(field).asText());
        } catch (NumberFormatException error) {
            throw new StoreUnavailableException("LEGACY_INVALID_DATA", error);
        }
    }

    @Override
    public Saved<Closing> latestClosing() {
        var node = exchange("/cierre-ultimo", null);
        if (!node.hasNonNull("id")) {
            if (node.has("message")) { return null; }
            throw new StoreUnavailableException("LEGACY_INVALID_DATA");
        }
        var closing = new Closing(decimal(node, "ice"), decimal(node, "deltamed"), decimal(node, "deltanwe"),
                decimal(node, "divisa"), decimal(node, "gna"), decimal(node, "gnanwe"), decimal(node, "gnamed"));
        return new Saved<>(node.path("id").asText(), node.path("created_at").asText(), closing);
    }

    @Override
    public Saved<Premiums> latestPremiums() {
        var node = exchange("/precios-ciudades-ultimo", null);
        if (!node.hasNonNull("id")) {
            if (node.has("message")) { return null; }
            throw new StoreUnavailableException("LEGACY_INVALID_DATA");
        }
        var premiums = new Premiums(decimal(node, "gasoilvigo"), decimal(node, "gasolinafirstvigo"),
                decimal(node, "gasolinasecondvigo"), decimal(node, "gasoilhuelva"),
                decimal(node, "gasolinafirsthuelva"), decimal(node, "gasolinasecondhuelva"),
                decimal(node, "gasoilmerida"));
        return new Saved<>(node.path("id").asText(), node.path("created_at").asText(), premiums);
    }

    @Override
    public List<Report> reports() {
        var nodes = exchange("/informes", null);
        if (!nodes.isArray()) { throw new StoreUnavailableException("LEGACY_INVALID_DATA"); }
        var result = new ArrayList<Report>();
        for (var node : nodes) {
            if (!node.hasNonNull("id") || !node.hasNonNull("texto") || !node.hasNonNull("fecha")) {
                throw new StoreUnavailableException("LEGACY_INVALID_DATA");
            }
            result.add(new Report(node.get("id").asText(), node.get("texto").asText(), node.get("fecha").asText()));
        }
        return List.copyOf(result);
    }

    private void write(String path, Object value) {
        var response = exchange(path, value);
        if (response == null || !response.hasNonNull("message")) {
            throw new StoreUnavailableException("LEGACY_WRITE_UNCONFIRMED");
        }
    }

    @Override
    public void saveClosing(Closing closing) { write("/insert-cierre", closing); }
    @Override
    public void savePremiums(Premiums premiums) { write("/insert-precios-ciudades", premiums); }
    @Override
    public void saveReport(ReportInput report) { write("/insert-informe", Map.of("texto", report.text())); }
    @Override
    public void close() { http.shutdownNow(); }
}
