package com.petroxpert.ms.services.business;

import com.petroxpert.ms.contract.BusinessDtos.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCalculationsTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private final Closing closing = new Closing(d("700"), d("20"), d("10"), d("1.1"),
            d("2"), d("650"), d("660"));

    @Test
    void reproducesConversionsAndAbsoluteChanges() {
        var result = LegacyCalculations.variations(d("720"), d("2.1"), d("1.1"), closing);
        assertEquals(d("560.77"), result.gasoilNwe());
        assertEquals(d("568.45"), result.gasoilMed());
        assertEquals(d("545.41"), result.closingGasoilNwe());
        assertEquals(d("15.36"), result.gasoilChange());
        assertEquals(d("24.02"), result.gasolineChange());
    }

    @Test
    void appliesRegionalBasesPremiumsAndSupplementAndLeavesMeridaGasolineAbsent() {
        var v = LegacyCalculations.variations(d("720"), d("2.1"), d("1.1"), closing);
        var p = new Premiums(d("10.1234"), d("11"), d("12"), d("13"), d("14"), d("15"), d("16"));
        var cities = LegacyCalculations.cities(v, closing, p);
        assertEquals(d("570.8934"), cities.get(0).goa());
        assertEquals(d("572.3934"), cities.get(0).goaPlus());
        assertEquals(d("685.02"), cities.get(0).gasoline95());
        assertEquals(d("686.52"), cities.get(0).gasoline95Plus());
        assertEquals(d("699.02"), cities.get(1).gasoline98());
        assertNull(cities.get(2).gasoline95());
    }

    @Test
    void rejectsZeroExchangeRate() {
        assertThrows(IllegalArgumentException.class,
                () -> LegacyCalculations.variations(d("700"), d("2"), BigDecimal.ZERO, closing));
        var invalid = new Closing(d("700"), d("20"), d("10"), BigDecimal.ZERO, d("2"), d("650"), d("660"));
        assertThrows(IllegalArgumentException.class,
                () -> LegacyCalculations.variations(d("700"), d("2"), d("1.1"), invalid));
    }
}
