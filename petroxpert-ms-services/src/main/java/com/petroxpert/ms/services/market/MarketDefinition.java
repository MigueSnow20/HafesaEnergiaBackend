package com.petroxpert.ms.services.market;

public enum MarketDefinition {
    GASOIL("London Gas Oil", "USD/t"),
    GASOLINE_RBOB("Gasolina RBOB", "USD/gal"),
    EUR_USD("EUR / USD", "USD/EUR");

    public final String displayName;
    public final String unit;

    MarketDefinition(String displayName, String unit) {
        this.displayName = displayName;
        this.unit = unit;
    }
}
