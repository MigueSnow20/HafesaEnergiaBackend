package com.petroxpert.ms.services.business;

import com.petroxpert.ms.contract.BusinessDtos.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/** Compatibility with VariationService.js and Precios.vue; B01-B10 remain unconfirmed. */
public final class LegacyCalculations {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal DENSITY = new BigDecimal("0.845");
    private static final BigDecimal GALLON = new BigDecimal("3.78541");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal PLUS = new BigDecimal("1.5");

    private LegacyCalculations() { }

    private static BigDecimal rounded(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }

    public static Variations variations(BigDecimal gasoil, BigDecimal gasoline, BigDecimal eur, Closing closing) {
        if (eur.signum() == 0 || closing.divisa().signum() == 0) {
            throw new IllegalArgumentException("ZERO_EXCHANGE_RATE");
        }
        var nwe = gasoil.add(closing.deltaNWE()).multiply(DENSITY).divide(eur, PRECISION);
        var med = gasoil.add(closing.deltaMed()).multiply(DENSITY).divide(eur, PRECISION);
        var last = closing.ice().add(closing.deltaNWE()).multiply(DENSITY).divide(closing.divisa(), PRECISION);
        var gasolineNow = gasoline.divide(eur, PRECISION).divide(GALLON, PRECISION).multiply(THOUSAND);
        var gasolineLast = closing.gna().divide(closing.divisa(), PRECISION)
                .divide(GALLON, PRECISION).multiply(THOUSAND);
        return new Variations(rounded(nwe), rounded(med), rounded(last), rounded(nwe.subtract(last)),
                rounded(gasolineNow.subtract(gasolineLast)), "EUR/m³");
    }

    public static List<CityPrice> cities(Variations v, Closing c, Premiums p) {
        return List.of(
                city("CLH VIGO", "NWE", v.gasoilNwe().add(p.gasoilVigo()),
                        v.gasolineChange().add(c.gnaNWE()), p.gasolinaFirstVigo(), p.gasolinaSecondVigo()),
                city("CLH HUELVA", "MED", v.gasoilMed().add(p.gasoilHuelva()),
                        v.gasolineChange().add(c.gnaMED()), p.gasolinaFirstHuelva(), p.gasolinaSecondHuelva()),
                new CityPrice("CLH MÉRIDA", "MED", v.gasoilMed().add(p.gasoilMerida()),
                        v.gasoilMed().add(p.gasoilMerida()).add(PLUS), null, null, null));
    }

    private static CityPrice city(String name, String reference, BigDecimal goa, BigDecimal gasolineBase,
                                  BigDecimal first, BigDecimal second) {
        var gasoline95 = gasolineBase.add(first);
        return new CityPrice(name, reference, goa, goa.add(PLUS), rounded(gasoline95),
                rounded(gasoline95.add(PLUS)), rounded(gasolineBase.add(second)));
    }
}
