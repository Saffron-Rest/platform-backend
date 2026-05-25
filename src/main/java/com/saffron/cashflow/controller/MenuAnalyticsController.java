package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.MenuAnalyticsService;
import com.saffron.cashflow.service.MenuEngineService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/menu/analytics")
public class MenuAnalyticsController {

    private final MenuAnalyticsService analytics;
    private final MenuEngineService engine;

    public MenuAnalyticsController(MenuAnalyticsService analytics, MenuEngineService engine) {
        this.analytics = analytics;
        this.engine = engine;
    }

    /** Period analytics — KPIs, top items, category mix. */
    @GetMapping
    public Map<String, Object> period(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.compute(from, to);
    }

    /** Menu engineering — adds Stars/Plowhorses/Puzzles/Dogs + actionable suggestions. */
    @GetMapping("/engineering")
    public Map<String, Object> engineering(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return engine.compute(from, to);
    }
}
