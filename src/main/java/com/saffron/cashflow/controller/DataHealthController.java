package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.DataHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/data-health")
public class DataHealthController {

    private final DataHealthService service;

    public DataHealthController(DataHealthService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> health() {
        return service.compute();
    }
}
