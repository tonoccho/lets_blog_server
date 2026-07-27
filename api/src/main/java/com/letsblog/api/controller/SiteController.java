package com.letsblog.api.controller;

import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.service.SiteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @PostMapping
    public ResponseEntity<SiteResponse> register(@Valid @RequestBody SiteRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(siteService.register(request));
    }

    @GetMapping
    public List<SiteResponse> list() {
        return siteService.list();
    }
}
