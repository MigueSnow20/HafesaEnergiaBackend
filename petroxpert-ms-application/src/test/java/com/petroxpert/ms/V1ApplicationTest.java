package com.petroxpert.ms;

import javax.sql.DataSource;
import com.petroxpert.ms.services.market.MarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"petroxpert.market.enabled=false", "legacy.base-url="})
@ActiveProfiles("v1")
@AutoConfigureMockMvc
@Import(V1ApplicationTest.ProviderConfiguration.class)
class V1ApplicationTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired MarketDataProvider provider;

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean @Primary
        MarketDataProvider testProvider() {
            var provider = mock(MarketDataProvider.class);
            when(provider.source(any())).thenReturn("test-only");
            return provider;
        }
    }

    @Test
    void startsWithoutDatabaseAndExposesInMemoryMarkets() throws Exception {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertFalse(context.containsBean("flyway"));
        mvc.perform(get("/api/v1/markets")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("GASOIL"))
                .andExpect(jsonPath("$[0].sourceStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].stale").value(true));
        mvc.perform(get("/api/v1/markets/summary")).andExpect(status().isOk())
                .andExpect(header().string("X-Market-Refresh-Ms", "5000"))
                .andExpect(jsonPath("$.calculationStatus").value("MISSING_INPUTS"));
        mvc.perform(get("/api/v1/markets/BRENT")).andExpect(status().isNotFound());
        var first = mvc.perform(get("/api/v1/markets")).andReturn().getResponse().getContentAsString();
        var second = mvc.perform(get("/api/v1/markets")).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        verify(provider, never()).fetch(any());
    }

    @Test
    void servesClientRoutesAndHealthWithoutAcquisition() throws Exception {
        for (String path : new String[] {"/", "/markets", "/closings", "/reports", "/cities"}) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verify(provider, never()).fetchQuote(any());
    }

    @Test
    void validatesWritesAndReturnsDegradedWithoutLeakingException() throws Exception {
        mvc.perform(post("/api/v1/closings").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reports").contentType("application/json").content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reports").contentType("application/json").content("{\"text\":\"Prueba\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("LEGACY_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void corsOnlyAllowsConfiguredFrontend() throws Exception {
        mvc.perform(options("/api/v1/markets").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/v1/markets").header("Origin", "https://unconfigured.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/markets/summary").header("Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Market-Refresh-Ms"));
    }
}
