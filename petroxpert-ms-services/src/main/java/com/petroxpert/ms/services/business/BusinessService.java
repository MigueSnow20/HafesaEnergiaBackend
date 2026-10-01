package com.petroxpert.ms.services.business;

import com.petroxpert.ms.contract.BusinessDtos.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BusinessService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessService.class);
    private final BusinessStore store;
    private final Duration refresh;
    private final Duration staleAfter;
    private final Duration maxBackoff;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
    private final Cached<Saved<Closing>> closing = new Cached<>();
    private final Cached<Saved<Premiums>> premiums = new Cached<>();
    private final Cached<List<Report>> reports = new Cached<>();

    private static final class Cached<T> {
        private volatile Availability<T> value = new Availability<>("PENDING", null, null, "Esperando datos");
        private int failures;
        private Instant next = Instant.MIN;

        synchronized void refresh(Supplier<T> read, String resource, Duration refresh, Duration maxBackoff) {
            if (Instant.now().isBefore(next)) { return; }
            try {
                T data = read.get();
                value = new Availability<>(data == null ? "EMPTY" : "OK", data, Instant.now(), null);
                failures = 0;
                next = Instant.MIN;
            } catch (RuntimeException error) {
                // Background integration boundary: isolate each resource and preserve the last successful read.
                String message = error instanceof StoreUnavailableException ? error.getMessage() : "LEGACY_ERROR";
                value = new Availability<>("DEGRADED", value.data(), value.fetchedAt(), message);
                failures++;
                long delay = Math.min(maxBackoff.toMillis(), refresh.toMillis() << Math.min(failures - 1, 10));
                next = Instant.now().plusMillis(delay);
                LOG.warn("event=legacy_refresh_failed resource={} reason={} failures={}", resource, message, failures);
                if (!(error instanceof StoreUnavailableException)) {
                    LOG.error("event=legacy_unexpected resource={}", resource, error);
                }
            }
        }

        synchronized void invalidate() {
            value = new Availability<>("PENDING", null, null, "Guardado; pendiente de lectura autoritativa");
            next = Instant.MIN;
        }
    }

    public BusinessService(BusinessStore store, Duration refresh, Duration staleAfter, Duration maxBackoff) {
        this.store = store;
        this.refresh = refresh;
        this.staleAfter = staleAfter;
        this.maxBackoff = maxBackoff;
    }

    public void start() {
        executor.scheduleWithFixedDelay(() -> closing.refresh(store::latestClosing, "closing", refresh, maxBackoff),
                0, refresh.toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> premiums.refresh(store::latestPremiums, "premiums", refresh, maxBackoff),
                0, refresh.toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> reports.refresh(store::reports, "reports", refresh, maxBackoff),
                0, refresh.toMillis(), TimeUnit.MILLISECONDS);
    }

    private <T> Availability<T> current(Cached<T> cached) {
        var value = cached.value;
        if ("OK".equals(value.status()) && !Instant.now().isBefore(value.fetchedAt().plus(staleAfter))) {
            return new Availability<>("STALE", value.data(), value.fetchedAt(), "Lectura legacy desactualizada");
        }
        return value;
    }

    public Availability<Saved<Closing>> closing() { return current(closing); }
    public Availability<Saved<Premiums>> premiums() { return current(premiums); }
    public Availability<List<Report>> reports() { return current(reports); }

    public void saveClosing(Closing value) {
        if (value.divisa().signum() == 0) { throw new IllegalArgumentException("La divisa no puede ser cero"); }
        store.saveClosing(value);
        closing.invalidate();
    }

    public void savePremiums(Premiums value) {
        store.savePremiums(value);
        premiums.invalidate();
    }

    public void saveReport(ReportInput value) {
        store.saveReport(value);
        reports.invalidate();
    }

    @Override
    public void close() { executor.shutdownNow(); }
}
