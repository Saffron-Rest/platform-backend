package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public Map<String, Object> search(
            @RequestParam("q") String query,
            @RequestParam(value = "type", required = false) Set<String> types) {
        return searchService.search(query, types);
    }
}
