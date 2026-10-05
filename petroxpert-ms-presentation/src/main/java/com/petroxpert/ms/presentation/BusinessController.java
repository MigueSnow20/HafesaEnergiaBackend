package com.petroxpert.ms.presentation;

import com.petroxpert.ms.contract.BusinessDtos.*;
import com.petroxpert.ms.services.business.BusinessService;
import com.petroxpert.ms.services.market.MarketService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("v1")
@RequestMapping("/api/v1")
public class BusinessController {
    private final BusinessService service;
    private final MarketService markets;

    public BusinessController(BusinessService service, MarketService markets) {
        this.service = service;
        this.markets = markets;
    }

    @GetMapping("/closings/latest")
    public Availability<Saved<Closing>> closing() { return service.closing(); }
    @GetMapping("/premiums/latest")
    public Availability<Saved<Premiums>> premiums() { return service.premiums(); }
    @GetMapping("/reports")
    public Availability<List<Report>> reports() { return service.reports(); }
    @GetMapping("/cities")
    public Summary cities() { return markets.summary(); }

    @PostMapping("/closings")
    public WriteResult closing(@Valid @RequestBody Closing value) {
        service.saveClosing(value);
        return saved();
    }

    @PostMapping("/premiums")
    public WriteResult premiums(@Valid @RequestBody Premiums value) {
        service.savePremiums(value);
        return saved();
    }

    @PostMapping("/reports")
    public WriteResult report(@Valid @RequestBody ReportInput value) {
        service.saveReport(value);
        return saved();
    }

    private WriteResult saved() {
        return new WriteResult("SAVED", "Guardado confirmado; los datos se releerán en el próximo ciclo");
    }
}
