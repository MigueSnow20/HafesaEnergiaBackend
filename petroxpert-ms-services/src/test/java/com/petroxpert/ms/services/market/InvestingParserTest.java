package com.petroxpert.ms.services.market;

import com.petroxpert.ms.services.market.provider.InvestingParser;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InvestingParserTest {
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"1.234,56;1234.56", "712,50;712.50", "1,0845;1.0845", "3,5217;3.5217"})
    void parsesSpanishSourceWithoutUsingSystemLocale(String raw, String expected) {
        assertEquals(new BigDecimal(expected), InvestingParser.parseSpanishNumber(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "--", "NaN", "12abc", "1.23", "1,23.4", "1.234,5 €", "1,2,3"})
    void rejectsAmbiguousOrMalformedPrices(String raw) {
        assertThrows(IllegalArgumentException.class, () -> InvestingParser.parseSpanishNumber(raw));
    }

    @Test
    void selectsInstrumentRatherThanOtherPrices() {
        String html = "<span>99,99</span><div data-test='instrument-price-last'>1.234,56</div>";
        assertEquals(new BigDecimal("1234.56"), InvestingParser.parseHtml(html));
        assertThrows(IllegalArgumentException.class, () -> InvestingParser.parseHtml("<p>Access denied</p>"));
    }
}
