package com.jrpay.notifications.preferences;

import com.jrpay.notifications.domain.Audience;
import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.domain.NotificationChannel;
import com.jrpay.notifications.domain.NotificationPreferencesEntity;
import com.jrpay.notifications.repository.NotificationPreferencesRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreferencesService {

    private final NotificationPreferencesRepository repo;

    public PreferencesService(NotificationPreferencesRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public NotificationPreferencesEntity getOrCreate(UUID userId, Audience audience) {
        return repo.findById(userId).orElseGet(() -> {
            NotificationPreferencesEntity entity = new NotificationPreferencesEntity(
                    userId, audience,
                    PreferenceMatrix.defaultChannels(null, null, false, false),
                    PreferenceMatrix.defaultCategories());
            return repo.save(entity);
        });
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel) {
        if (channel == NotificationChannel.INBOX) return true;
        if (category.isLocked()) return true;
        return repo.findById(userId)
                .map(p -> PreferenceMatrix.isEnabled(p.getCategories(), category, channel)
                        && isChannelEnabled(p.getChannels(), channel))
                .orElse(false);
    }

    @Transactional
    public NotificationPreferencesEntity replace(UUID userId, Audience audience, Update update) {
        NotificationPreferencesEntity entity = repo.findById(userId)
                .orElseGet(() -> new NotificationPreferencesEntity(
                        userId, audience,
                        PreferenceMatrix.defaultChannels(null, null, false, false),
                        PreferenceMatrix.defaultCategories()));
        if (entity.getVersion() != update.expectedVersion()) {
            throw new ConcurrencyConflictException(entity.getVersion(), update.expectedVersion());
        }
        Map<String, Object> newChannels = mergeChannels(entity.getChannels(), update.channels());
        Map<String, Object> newCategories = PreferenceMatrix.applyOverrides(
                update.categories() == null || update.categories().isEmpty()
                        ? entity.getCategories() : update.categories());
        entity.replace(newChannels, newCategories);
        return repo.save(entity);
    }

    private static boolean isChannelEnabled(Map<String, Object> channels, NotificationChannel channel) {
        Object cell = channels.get(channel.name().toLowerCase());
        if (!(cell instanceof Map<?, ?> m)) return false;
        return Boolean.TRUE.equals(m.get("enabled"));
    }

    private static Map<String, Object> mergeChannels(Map<String, Object> existing,
                                                      Map<String, Object> override) {
        if (override == null || override.isEmpty()) return existing;
        Map<String, Object> merged = new java.util.LinkedHashMap<>(existing);
        for (var e : override.entrySet()) {
            String key = e.getKey();
            if ("inbox".equals(key)) continue; // inbox is locked-on
            Object existingCell = merged.getOrDefault(key, new java.util.LinkedHashMap<>());
            Map<String, Object> channelMap = existingCell instanceof Map<?, ?> m
                    ? new java.util.LinkedHashMap<>(toStringKeyed(m))
                    : new java.util.LinkedHashMap<>();
            if (e.getValue() instanceof Map<?, ?> patch) {
                Object enabled = ((Map<?, ?>) patch).get("enabled");
                if (enabled != null) channelMap.put("enabled", enabled);
            }
            merged.put(key, channelMap);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringKeyed(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    public record Update(int expectedVersion,
                         Map<String, Object> channels,
                         Map<String, Object> categories) { }

    public static class ConcurrencyConflictException extends RuntimeException {
        private final int serverVersion;
        public ConcurrencyConflictException(int serverVersion, int clientVersion) {
            super("Preferences version mismatch: server=" + serverVersion + " client=" + clientVersion);
            this.serverVersion = serverVersion;
        }
        public int getServerVersion() { return serverVersion; }
    }
}
