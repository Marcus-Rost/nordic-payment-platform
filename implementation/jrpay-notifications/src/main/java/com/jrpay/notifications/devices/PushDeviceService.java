package com.jrpay.notifications.devices;

import com.jrpay.notifications.domain.PushDevice;
import com.jrpay.notifications.repository.PushDeviceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Push-device registration. Tokens are stored only as SHA-256; the raw
 * token never persists per FS.15 / SSE_CONVENTIONS.md log hygiene.
 */
@Service
public class PushDeviceService {

    private final PushDeviceRepository repo;

    public PushDeviceService(PushDeviceRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<PushDevice> list(UUID userId) {
        return repo.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId);
    }

    /**
     * Register or refresh a push device. Returns
     * {@link Outcome#REGISTERED} for a brand-new row, {@link Outcome#REFRESHED}
     * when the same token was already on file (idempotent re-register).
     */
    @Transactional
    public Result register(UUID userId, RegisterRequest request) {
        String tokenHash = sha256Hex(request.token());
        var existing = repo.findByUserIdAndTokenHash(userId, tokenHash);
        if (existing.isPresent()) {
            PushDevice d = existing.get();
            d.touch();
            repo.save(d);
            return new Result(d, Outcome.REFRESHED);
        }
        PushDevice device = new PushDevice(
                UUID.randomUUID(), userId, request.platform(), tokenHash,
                request.name(), request.model(), request.osVersion(),
                request.appVersion(), request.bundleId());
        repo.save(device);
        return new Result(device, Outcome.REGISTERED);
    }

    @Transactional
    public boolean revoke(UUID userId, UUID deviceId) {
        var device = repo.findById(deviceId).orElse(null);
        if (device == null || !device.getUserId().equals(userId)) return false;
        if (device.isActive()) {
            device.revoke();
            repo.save(device);
        }
        return true;
    }

    public record RegisterRequest(
            PushDevice.Platform platform,
            String token,
            String name,
            String model,
            String osVersion,
            String appVersion,
            String bundleId
    ) { }

    public record Result(PushDevice device, Outcome outcome) { }

    public enum Outcome { REGISTERED, REFRESHED }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
