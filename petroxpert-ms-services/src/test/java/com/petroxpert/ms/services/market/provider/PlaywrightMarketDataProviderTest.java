package com.petroxpert.ms.services.market.provider;

import com.microsoft.playwright.*;
import com.petroxpert.ms.services.market.MarketDefinition;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightMarketDataProviderTest {
    @Test
    void reusesBrowserClosesEachContextAndRejectsHttpBlockBeforeReadingPrice() {
        var playwright = mock(Playwright.class);
        var chromium = mock(BrowserType.class);
        var browser = mock(Browser.class);
        var context = mock(BrowserContext.class);
        var page = mock(Page.class);
        var response = mock(Response.class);
        var price = mock(Locator.class);
        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any())).thenReturn(browser);
        when(browser.isConnected()).thenReturn(true);
        when(browser.newContext()).thenReturn(context);
        when(context.newPage()).thenReturn(page);
        when(page.navigate(anyString(), any())).thenReturn(response);
        when(response.status()).thenReturn(200);
        when(page.locator(InvestingSources.PRICE_SELECTOR)).thenReturn(price);
        when(price.first()).thenReturn(price);
        when(price.innerText(any())).thenReturn("712,50", "1,0845");
        {
            var provider = new PlaywrightMarketDataProvider(Duration.ofSeconds(8), () -> playwright);
            try (provider) {
                assertEquals(new BigDecimal("712.50"), provider.fetch(MarketDefinition.GASOIL));
                assertEquals(new BigDecimal("1.0845"), provider.fetch(MarketDefinition.EUR_USD));
                when(response.status()).thenReturn(403);
                assertEquals("SOURCE_HTTP_403", assertThrows(IllegalArgumentException.class,
                        () -> provider.fetch(MarketDefinition.GASOLINE_RBOB)).getMessage());
                verify(chromium, times(1)).launch(any());
                verify(context, times(3)).close();
                verify(price, times(2)).innerText(any());
            }
            provider.close();
            verify(browser, times(1)).close();
            verify(playwright, times(1)).close();
            assertThrows(IllegalStateException.class, () -> provider.fetch(MarketDefinition.GASOIL));
        }
    }
}
