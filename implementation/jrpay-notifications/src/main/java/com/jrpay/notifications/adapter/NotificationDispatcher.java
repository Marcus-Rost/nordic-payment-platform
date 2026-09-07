package com.jrpay.notifications.adapter;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationChannel;
import com.jrpay.notifications.domain.NotificationPreferencesEntity;
import com.jrpay.notifications.domain.PushDevice;
import com.jrpay.notifications.inbox.InboxService;
import com.jrpay.notifications.preferences.PreferenceMatrix;
import com.jrpay.notifications.preferences.PreferencesService;
import com.jrpay.notifications.repository.NotificationPreferencesRepository;
import com.jrpay.notifications.repository.PushDeviceRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a notification to the inbox and dispatches it to every enabled
 * outbound channel per the customer's preference matrix.
 *
 * <p>Inbox is mandatory and runs even when the side-channel adapters fail.
 * Adapter failures are logged + recorded as part of the dispatch result so
 * the caller can decide whether to alert (e.g. payment-status-updates
 * Kafka projector treats inbox-success as the only commit gate).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final InboxService inbox;
    private final PreferencesService preferences;
    private final NotificationPreferencesRepository preferencesRepo;
    private final PushDeviceRepository devices;
    private final Map<NotificationChannel, NotificationChannelAdapter> adapters =
            new EnumMap<>(NotificationChannel.class);

    public NotificationDispatcher(InboxService inbox,
                                  PreferencesService preferences,
                                  NotificationPreferencesRepository preferencesRepo,
                                  PushDeviceRepository devices,
                                  List<NotificationChannelAdapter> adapterBeans) {
        this.inbox = inbox;
        this.preferences = preferences;
        this.preferencesRepo = preferencesRepo;
        this.devices = devices;
        for (NotificationChannelAdapter adapter : adapterBeans) {
            adapters.put(adapter.channel(), adapter);
        }
        log.info("Notification dispatcher wired with adapters={}", adapters.keySet());
    }

    @Transactional
    public DispatchResult dispatch(Notification notification) {
        // Bootstraps the user's preferences row if missing (e.g., first-time
        // notification) so the matrix lookup below has something to consult.
        preferences.getOrCreate(notification.getUserId(), notification.getAudience());

        Notification persisted = inbox.persistNew(notification);

        NotificationPreferencesEntity prefs = preferencesRepo.findById(notification.getUserId())
                .orElseThrow();
        Recipient recipient = new Recipient(
                prefs.getEmailAddress(), prefs.getPhoneNumber(),
                devices.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(notification.getUserId())
                        .stream().map(PushDevice::getTokenHash).toList()
        );
        var dispatchRecipient = new NotificationChannelAdapter.Recipient(
                notification.getUserId(),
                recipient.emailAddress(), recipient.phoneNumber(), recipient.pushDeviceTokens()
        );

        List<NotificationChannelAdapter.Result> results = new ArrayList<>();
        for (NotificationChannel channel : List.of(
                NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH)) {
            if (!PreferenceMatrix.isEnabled(prefs.getCategories(), notification.getCategory(), channel)) {
                results.add(NotificationChannelAdapter.Result.skipped(channel, "preference-off"));
                continue;
            }
            NotificationChannelAdapter adapter = adapters.get(channel);
            if (adapter == null) {
                results.add(NotificationChannelAdapter.Result.skipped(channel, "no-adapter"));
                continue;
            }
            try {
                results.add(adapter.dispatch(persisted, dispatchRecipient));
            } catch (Exception e) {
                log.warn("Adapter {} failed for notification {}: {}",
                        channel, persisted.getNotificationId(), e.toString());
                results.add(NotificationChannelAdapter.Result.failed(channel, e.getClass().getSimpleName()));
            }
        }
        return new DispatchResult(persisted, results);
    }

    private record Recipient(String emailAddress, String phoneNumber,
                             java.util.List<String> pushDeviceTokens) { }

    public record DispatchResult(Notification notification,
                                 List<NotificationChannelAdapter.Result> channelResults) { }
}
