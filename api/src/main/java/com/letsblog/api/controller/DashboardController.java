package com.letsblog.api.controller;

import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.service.ConnectedServiceStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ConnectedServiceStatusService connectedServiceStatusService;

    public DashboardController(ConnectedServiceStatusService connectedServiceStatusService) {
        this.connectedServiceStatusService = connectedServiceStatusService;
    }

    @GetMapping("/service-status")
    public List<ConnectedServiceStatusResponse> getServiceStatus() {
        return connectedServiceStatusService.checkAll();
    }
}
