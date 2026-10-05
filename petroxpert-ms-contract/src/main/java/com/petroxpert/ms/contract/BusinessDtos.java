package com.petroxpert.ms.contract;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BusinessDtos {
    private BusinessDtos() { }

    public record Closing(
            @NotNull @Digits(integer = 8, fraction = 4) BigDecimal ice,
            @NotNull @Digits(integer = 8, fraction = 4) BigDecimal deltaMed,
            @NotNull @Digits(integer = 8, fraction = 4) BigDecimal deltaNWE,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal divisa,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gna,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gnaNWE,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gnaMED) { }

    public record Premiums(
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasoilVigo,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasolinaFirstVigo,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasolinaSecondVigo,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasoilHuelva,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasolinaFirstHuelva,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasolinaSecondHuelva,
            @NotNull @Digits(integer = 6, fraction = 4) BigDecimal gasoilMerida) { }

    public record ReportInput(@NotBlank @Size(max = 50000) String text) { }
    public record Report(String id, String text, String recordedAt) { }
    public record Saved<T>(String id, String recordedAt, T values) { }
    public record Availability<T>(String status, T data, Instant fetchedAt, String message) { }
    public record WriteResult(String status, String message) { }
    public record Variations(BigDecimal gasoilNwe, BigDecimal gasoilMed, BigDecimal closingGasoilNwe,
                             BigDecimal gasoilChange, BigDecimal gasolineChange, String unit) { }
    public record CityPrice(String city, String reference, BigDecimal goa, BigDecimal goaPlus,
                            BigDecimal gasoline95, BigDecimal gasoline95Plus, BigDecimal gasoline98) { }
    public record Summary(List<MarketSnapshot> markets, Availability<Saved<Closing>> closing,
                          Availability<Saved<Premiums>> premiums, Availability<List<Report>> reports,
                          Variations variations, List<CityPrice> cities, boolean stale, String calculationStatus) { }
}
