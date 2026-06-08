package com.gb.admin.domain.dashboard.service;

import com.gb.admin.domain.dashboard.dto.response.DashboardAlertsResponse;
import com.gb.admin.domain.dashboard.dto.response.DashboardSummaryResponse;

public interface DashboardService {

    DashboardSummaryResponse getSummary();

    DashboardAlertsResponse getAlerts();
}
