package com.gb.admin.domain.monitoring.service;

import com.gb.admin.domain.monitoring.dto.response.AuthFailuresResponse;
import com.gb.admin.domain.monitoring.dto.response.BusinessAnalyticsResponse;
import com.gb.admin.domain.monitoring.dto.response.ConfigResponse;
import com.gb.admin.domain.monitoring.dto.response.DomainSloResponse;
import com.gb.admin.domain.monitoring.dto.response.InfraAlertsResponse;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse;
import com.gb.admin.domain.monitoring.dto.response.QueuesResponse;
import com.gb.admin.domain.monitoring.dto.response.ServiceHealthResponse;

public interface MonitoringService {

    ServiceHealthResponse serviceHealth();

    DomainSloResponse domainSlo();

    QueuesResponse queues();

    AuthFailuresResponse authFailures();

    ConfigResponse config();

    EmbedsResponse embeds();

    BusinessAnalyticsResponse businessAnalytics();

    InfraAlertsResponse infraAlerts();
}
