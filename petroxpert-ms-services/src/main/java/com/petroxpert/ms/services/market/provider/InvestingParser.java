package com.petroxpert.ms.services.market.provider;

import java.math.BigDecimal;
import org.jsoup.Jsoup;

public final class InvestingParser {
    private InvestingParser() { }

    public static BigDecimal parseHtml(String html) {
        var element = Jsoup.parse(html).selectFirst(InvestingSources.PRICE_SELECTOR);
        if (element == null) {
            throw new IllegalArgumentException("PRICE_SELECTOR_MISSING");
        }
        return parseSpanishNumber(element.text());
    }

    public static BigDecimal parseSpanishNumber(String text) {
        String clean = text.strip().replace('\u00a0', ' ').replace('\u202f', ' ');
        if (!clean.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]+)?")) {
            throw new IllegalArgumentException("INVALID_PRICE_FORMAT");
        }
        return new BigDecimal(clean.replace(".", "").replace(',', '.'));
    }
}
