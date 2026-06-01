package com.saffron.cashflow.controller;

import com.saffron.cashflow.service.SupplierService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the vendor-master used by the accounts-payable
 * module. Mounted at {@code /api/suppliers}.
 *
 * <p>Read access requires {@code PAYABLES_VIEW} or {@code PAYABLES_MANAGE}
 * (managers see the picker by default); write access requires
 * {@code PAYABLES_MANAGE} or admin.</p>
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(name = "includeInactive", required = false, defaultValue = "false")
            boolean includeInactive) {
        return supplierService.list(includeInactive);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return supplierService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return supplierService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return supplierService.update(id, body);
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id) {
        supplierService.deactivate(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable String id) {
        supplierService.reactivate(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        supplierService.deletePermanently(id);
        return Map.of("ok", true);
    }
}
