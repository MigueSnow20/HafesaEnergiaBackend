package com.petroxpert.ms.presentation;

import com.petroxpert.ms.contract.MarketSnapshot;
import com.petroxpert.ms.contract.BusinessDtos.Summary;
import com.petroxpert.ms.services.market.MarketDefinition;
import com.petroxpert.ms.services.market.MarketService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("v1")
@RequestMapping("/api/v1/markets")
public class MarketController {
    private final MarketService service;
    private final long refreshIntervalMs;

    public MarketController(MarketService service,
                            @Value("${petroxpert.market.refresh-interval}") String refresh) {
        this.service = service;
        this.refreshIntervalMs = DurationStyle.detectAndParse(refresh).toMillis();
    }

    @GetMapping
    public List<MarketSnapshot> all() { return service.all(); }

    @GetMapping("/summary")
    public ResponseEntity<Summary> summary() {
        return ResponseEntity.ok().header("X-Market-Refresh-Ms", Long.toString(refreshIntervalMs))
                .body(service.summary());
    }

    @GetMapping("/{marketCode}")
    public MarketSnapshot one(@PathVariable String marketCode) {
        try {
            return service.get(MarketDefinition.valueOf(marketCode));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mercado no disponible");
        }
    }
}
