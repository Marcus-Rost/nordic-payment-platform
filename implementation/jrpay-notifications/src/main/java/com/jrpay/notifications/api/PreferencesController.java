package com.jrpay.notifications.api;

import com.jrpay.notifications.config.AuthenticatedUser;
import com.jrpay.notifications.domain.NotificationPreferencesEntity;
import com.jrpay.notifications.preferences.PreferencesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/notifications/preferences")
public class PreferencesController {

    private final PreferencesService preferences;
    private final AuthenticatedUser caller;

    public PreferencesController(PreferencesService preferences, AuthenticatedUser caller) {
        this.preferences = preferences;
        this.caller = caller;
    }

    @GetMapping
    public Map<String, Object> get() {
        UUID userId = caller.requireUserId();
        NotificationPreferencesEntity entity = preferences.getOrCreate(userId, caller.currentAudience());
        return view(entity);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> put(@Valid @RequestBody UpdateBody body) {
        UUID userId = caller.requireUserId();
        try {
            NotificationPreferencesEntity entity = preferences.replace(userId, caller.currentAudience(),
                    new PreferencesService.Update(body.version, body.channels, body.categories));
            return ResponseEntity.ok(view(entity));
        } catch (PreferencesService.ConcurrencyConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "type", "urn:problem:idempotency-key-conflict",
                    "title", "Preferences version mismatch",
                    "status", 409,
                    "details", Map.of("serverVersion", e.getServerVersion())
            ));
        }
    }

    private Map<String, Object> view(NotificationPreferencesEntity entity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", entity.getVersion());
        body.put("channels", entity.getChannels());
        body.put("categories", entity.getCategories());
        return body;
    }

    public static final class UpdateBody {
        @NotNull public Integer version;
        public Map<String, Object> channels;
        @NotNull public Map<String, Object> categories;
    }
}
