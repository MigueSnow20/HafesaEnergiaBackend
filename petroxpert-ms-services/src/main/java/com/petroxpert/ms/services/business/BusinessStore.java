package com.petroxpert.ms.services.business;

import com.petroxpert.ms.contract.BusinessDtos.*;
import java.util.List;

/** Persistence boundary: V1 HTTP adapter, later PostgreSQL implementation. */
public interface BusinessStore {
    Saved<Closing> latestClosing();
    Saved<Premiums> latestPremiums();
    List<Report> reports();
    void saveClosing(Closing closing);
    void savePremiums(Premiums premiums);
    void saveReport(ReportInput report);
}
