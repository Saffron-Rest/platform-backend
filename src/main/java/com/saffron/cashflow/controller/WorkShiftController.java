package com.saffron.cashflow.controller;

import com.saffron.cashflow.dto.AssignShiftRequest;
import com.saffron.cashflow.dto.UpsertScheduleRequest;
import org.springframework.http.HttpStatus;
import com.saffron.cashflow.service.WorkShiftService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shifts")
public class WorkShiftController {

    private final WorkShiftService workShiftService;

    public WorkShiftController(WorkShiftService workShiftService) {
        this.workShiftService = workShiftService;
    }

    @GetMapping("/today")
    public Map<String, Object> today(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String userId) {
        return workShiftService.getToday(date, userId);
    }

    @GetMapping("/range")
    public Map<String, List<Map<String, Object>>> range(
            @RequestParam String from,
            @RequestParam String to) {
        return workShiftService.listForRange(from, to);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String date) {
        return workShiftService.listForDate(date);
    }

    @PostMapping("/assign")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assign(@Valid @RequestBody AssignShiftRequest request) {
        return workShiftService.assign(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        workShiftService.deleteShift(id);
    }

    @PutMapping
    public List<Map<String, Object>> upsert(@Valid @RequestBody UpsertScheduleRequest request) {
        return workShiftService.upsertSchedule(request);
    }
}
