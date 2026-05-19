package com.saffron.cashflow.service;

import com.saffron.cashflow.domain.AuditAction;
import com.saffron.cashflow.domain.SystemSetting;
import com.saffron.cashflow.util.AuditSnapshots;
import com.saffron.cashflow.repository.SystemSettingRepository;
import com.saffron.cashflow.security.AuthHelper;
import com.saffron.cashflow.util.ShiftHoursUtil;
import com.saffron.cashflow.util.WeeklyOperatingHours;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SettingsService {

    private static final String PLATFORMS_KEY = "platforms";
    private static final String PAYROLL_KEY = "payroll";

    private final SystemSettingRepository settingRepository;
    private final AuditService auditService;

    public SettingsService(SystemSettingRepository settingRepository, AuditService auditService) {
        this.settingRepository = settingRepository;
        this.auditService = auditService;
    }

    public Map<String, Object> getPlatforms() {
        return Map.of("platforms", loadPlatforms());
    }

    public Map<String, Object> getPayrollSettings() {
        AuthHelper.requireAdmin();
        WeeklyOperatingHours weekly = loadWeeklyHours();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weeklyHours", weekly.toApiMap());
        result.put("storeCloseTime", formatTime(weekly.closeFor(LocalDate.now())));
        return result;
    }

    public WeeklyOperatingHours loadWeeklyHours() {
        return settingRepository.findById(PAYROLL_KEY)
                .map(s -> {
                    Map<String, Object> value = s.getValue();
                    if (value.containsKey("weeklyHours") && value.get("weeklyHours") instanceof Map<?, ?> wh) {
                        @SuppressWarnings("unchecked")
                        Map<String, ?> map = (Map<String, ?>) wh;
                        return WeeklyOperatingHours.fromApiMap(map);
                    }
                    Object legacy = value.get("storeCloseTime");
                    LocalTime close = ShiftHoursUtil.parseTime(
                            legacy != null ? legacy.toString() : null,
                            ShiftHoursUtil.DEFAULT_STORE_CLOSE);
                    return WeeklyOperatingHours.fromSingleClose(close);
                })
                .orElse(WeeklyOperatingHours.defaults());
    }

    /** @deprecated use loadWeeklyHours().closeFor(date) */
    public LocalTime loadStoreCloseTime() {
        return loadWeeklyHours().closeFor(LocalDate.now());
    }

    public LocalTime closeForDate(LocalDate date) {
        return loadWeeklyHours().closeFor(date);
    }

    @Transactional
    public Map<String, Object> updatePayrollSettings(Map<String, Object> body) {
        AuthHelper.requireAdmin();
        Map<String, Object> before = settingRepository.findById(PAYROLL_KEY)
                .map(s -> AuditSnapshots.sanitize(s.getValue()))
                .orElse(Map.of());
        SystemSetting setting = settingRepository.findById(PAYROLL_KEY).orElse(new SystemSetting());
        setting.setKey(PAYROLL_KEY);

        Map<String, Object> value = new LinkedHashMap<>();
        if (body.containsKey("weeklyHours") && body.get("weeklyHours") instanceof Map<?, ?> wh) {
            @SuppressWarnings("unchecked")
            Map<String, ?> weeklyRaw = (Map<String, ?>) wh;
            WeeklyOperatingHours weekly = WeeklyOperatingHours.fromApiMap(weeklyRaw);
            value.put("weeklyHours", weekly.toApiMap());
        } else if (body.get("storeCloseTime") != null) {
            LocalTime parsed = ShiftHoursUtil.parseTime(
                    body.get("storeCloseTime").toString(),
                    ShiftHoursUtil.DEFAULT_STORE_CLOSE);
            value.put("weeklyHours", WeeklyOperatingHours.fromSingleClose(parsed).toApiMap());
        } else {
            throw new com.saffron.cashflow.web.BadRequestException("weeklyHours or storeCloseTime required");
        }
        setting.setValue(value);
        settingRepository.save(setting);
        Map<String, Object> result = getPayrollSettings();
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "Settings", PAYROLL_KEY,
                before, AuditSnapshots.sanitize(value), null);
        return result;
    }

    private static String formatTime(LocalTime t) {
        return t == null ? "22:00" : t.toString().substring(0, 5);
    }

    @Transactional
    public Map<String, Object> updatePlatforms(Map<String, Boolean> platforms) {
        AuthHelper.requireAdmin();
        Map<String, Object> before = new LinkedHashMap<>();
        loadPlatforms().forEach(before::put);
        SystemSetting setting = settingRepository.findById(PLATFORMS_KEY).orElse(new SystemSetting());
        setting.setKey(PLATFORMS_KEY);
        setting.setValue(new LinkedHashMap<>(platforms));
        settingRepository.save(setting);
        auditService.logChange(AuthHelper.currentUser().id(), AuditAction.UPDATE, "Settings", PLATFORMS_KEY,
                before, new LinkedHashMap<String, Object>(platforms), null);
        return Map.of("platforms", platforms);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Boolean> loadPlatforms() {
        return settingRepository.findById(PLATFORMS_KEY)
                .map(s -> (Map<String, Boolean>) (Map<?, ?>) s.getValue())
                .orElse(defaultPlatforms());
    }

    public static Map<String, Boolean> defaultPlatforms() {
        return Map.of("wolt", true, "bolt", true, "uberEats", true, "glovo", true, "other", true);
    }
}
