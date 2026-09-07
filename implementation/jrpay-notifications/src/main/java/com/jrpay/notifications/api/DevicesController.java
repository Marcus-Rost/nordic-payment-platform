package com.jrpay.notifications.api;

import com.jrpay.notifications.config.AuthenticatedUser;
import com.jrpay.notifications.devices.PushDeviceService;
import com.jrpay.notifications.domain.PushDevice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/notifications/devices")
public class DevicesController {

    private final PushDeviceService devices;
    private final AuthenticatedUser caller;

    public DevicesController(PushDeviceService devices, AuthenticatedUser caller) {
        this.devices = devices;
        this.caller = caller;
    }

    @GetMapping
    public Map<String, Object> list() {
        UUID userId = caller.requireUserId();
        List<Map<String, Object>> items = devices.list(userId).stream()
                .map(DevicesController::view).toList();
        return Map.of("items", items);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterBody body) {
        UUID userId = caller.requireUserId();
        var result = devices.register(userId, new PushDeviceService.RegisterRequest(
                PushDevice.Platform.valueOf(body.platform),
                body.token,
                body.name,
                body.model,
                body.osVersion,
                body.appVersion,
                body.bundleId));
        Map<String, Object> view = view(result.device());
        return ResponseEntity
                .status(result.outcome() == PushDeviceService.Outcome.REGISTERED
                        ? HttpStatus.CREATED : HttpStatus.OK)
                .body(view);
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> remove(@PathVariable UUID deviceId) {
        UUID userId = caller.requireUserId();
        return devices.revoke(userId, deviceId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static Map<String, Object> view(PushDevice d) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", d.getDeviceId());
        v.put("platform", d.getPlatform().name());
        v.put("name", d.getName());
        if (d.getModel() != null) v.put("model", d.getModel());
        if (d.getOsVersion() != null) v.put("osVersion", d.getOsVersion());
        if (d.getAppVersion() != null) v.put("appVersion", d.getAppVersion());
        v.put("registeredAt", d.getRegisteredAt());
        v.put("lastSeenAt", d.getLastSeenAt());
        return v;
    }

    public static final class RegisterBody {
        @NotBlank public String platform;
        @NotBlank public String token;
        @NotNull public String name;
        public String model;
        public String osVersion;
        public String appVersion;
        public String bundleId;
    }
}
