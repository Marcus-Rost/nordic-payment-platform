package com.jrpay.notifications.inbox;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.repository.NotificationRepository;
import com.jrpay.notifications.sse.NotificationEventBus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox CRUD facade. Wraps the repository with:
 * <ul>
 *   <li>Lock-aware soft-delete (UC-G80 AF-07 — SECURITY_EVENTS rows can't be
 *       deleted; the entity throws and we map to 409).
 *   <li>SSE fan-out via {@link NotificationEventBus} so every device on the
 *       same user gets {@code notification.read-state-changed} events.
 *   <li>Bulk mark-all-read with optional category + olderThan filter.
 * </ul>
 */
@Service
public class InboxService {

    private final NotificationRepository repository;
    private final NotificationEventBus bus;

    public InboxService(NotificationRepository repository, NotificationEventBus bus) {
        this.repository = repository;
        this.bus = bus;
    }

    @Transactional
    public Notification persistNew(Notification n) {
        Notification saved = repository.save(n);
        bus.publish(NotificationEventBus.Event.created(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public InboxPage list(InboxQuery q) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        Page<Notification> page = repository.search(
                q.userId(),
                q.categoryFilter() == null || q.categoryFilter().isEmpty() ? null : q.categoryFilter(),
                q.readState() == ReadState.UNREAD,
                q.readState() == ReadState.READ,
                q.from(), q.to(), q.modifiedSince(),
                q.includeDeleted(),
                PageRequest.of(q.page(), q.size(), sort));
        long unread = repository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(q.userId());
        return new InboxPage(page.getContent(), page.getTotalElements(), page.getNumber(),
                page.getSize(), page.getTotalPages(), unread);
    }

    @Transactional(readOnly = true)
    public Optional<Notification> get(UUID userId, String notificationId) {
        return repository.findById(notificationId)
                .filter(n -> n.getUserId().equals(userId));
    }

    @Transactional
    public Notification updateState(UUID userId, String notificationId,
                                    ReadStateChange readChange,
                                    DeleteStateChange deleteChange) {
        Notification n = get(userId, notificationId)
                .orElseThrow(() -> new InboxNotFoundException(notificationId));
        if (readChange == ReadStateChange.MARK_READ) {
            n.markRead();
        } else if (readChange == ReadStateChange.MARK_UNREAD) {
            n.markUnread();
        }
        if (deleteChange == DeleteStateChange.SOFT_DELETE) {
            n.markDeleted();
        } else if (deleteChange == DeleteStateChange.RESTORE) {
            n.restore();
        }
        Notification saved = repository.save(n);
        bus.publish(NotificationEventBus.Event.stateChanged(saved));
        return saved;
    }

    @Transactional
    public int markAllRead(UUID userId, List<NotificationCategory> categoryFilter,
                           OffsetDateTime olderThan) {
        int updated = repository.markAllReadForUser(
                userId, OffsetDateTime.now(), olderThan,
                categoryFilter == null || categoryFilter.isEmpty() ? null : categoryFilter);
        if (updated > 0) {
            bus.publish(NotificationEventBus.Event.bulkReadStateChanged(userId));
        }
        return updated;
    }

    public enum ReadState { ALL, UNREAD, READ }

    public enum ReadStateChange { NONE, MARK_READ, MARK_UNREAD }

    public enum DeleteStateChange { NONE, SOFT_DELETE, RESTORE }

    public record InboxQuery(
            UUID userId,
            List<NotificationCategory> categoryFilter,
            ReadState readState,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime modifiedSince,
            boolean includeDeleted,
            int page,
            int size
    ) { }

    public record InboxPage(
            List<Notification> items,
            long totalElements,
            int page,
            int size,
            int totalPages,
            long unreadCount
    ) { }

    public static class InboxNotFoundException extends RuntimeException {
        public InboxNotFoundException(String notificationId) {
            super("Notification not found: " + notificationId);
        }
    }
}
