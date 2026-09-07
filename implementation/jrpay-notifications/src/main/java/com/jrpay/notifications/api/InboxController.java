package com.jrpay.notifications.api;

import com.jrpay.notifications.config.AuthenticatedUser;
import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationCategory;
import com.jrpay.notifications.inbox.InboxService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/notifications")
public class InboxController {

    private final InboxService inbox;
    private final AuthenticatedUser caller;

    public InboxController(InboxService inbox, AuthenticatedUser caller) {
        this.inbox = inbox;
        this.caller = caller;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "category", required = false) List<NotificationCategory> category,
            @RequestParam(value = "readState", defaultValue = "all") String readState,
            @RequestParam(value = "from", required = false) OffsetDateTime from,
            @RequestParam(value = "to", required = false) OffsetDateTime to,
            @RequestParam(value = "modifiedSince", required = false) OffsetDateTime modifiedSince,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        UUID userId = caller.requireUserId();
        var query = new InboxService.InboxQuery(userId, category, readStateOf(readState),
                from, to, modifiedSince, includeDeleted, page, size);
        var result = inbox.list(query);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(this::view).toList());
        body.put("page", Map.of(
                "page", result.page(),
                "size", result.size(),
                "totalElements", result.totalElements(),
                "totalPages", result.totalPages()));
        body.put("unreadCount", result.unreadCount());
        return body;
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String notificationId) {
        UUID userId = caller.requireUserId();
        return inbox.get(userId, notificationId)
                .map(n -> ResponseEntity.ok(view(n)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PatchMapping("/{notificationId}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String notificationId,
                                                     @Valid @RequestBody PatchBody body) {
        UUID userId = caller.requireUserId();
        InboxService.ReadStateChange readChange = readChangeOf(body);
        InboxService.DeleteStateChange deleteChange = deleteChangeOf(body);
        if (readChange == InboxService.ReadStateChange.NONE
                && deleteChange == InboxService.DeleteStateChange.NONE) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "type", "urn:problem:invalid-input",
                    "title", "PATCH body must include readAt or deletedAt",
                    "status", 400
            ));
        }
        try {
            Notification updated = inbox.updateState(userId, notificationId, readChange, deleteChange);
            return ResponseEntity.ok(view(updated));
        } catch (InboxService.InboxNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Notification.LockedNotificationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "type", "urn:problem:pending-execution",
                    "title", "Notification is locked",
                    "status", 409,
                    "details", Map.of("reason", "LOCKED_CATEGORY")
            ));
        }
    }

    @PostMapping("/mark-all-read")
    public Map<String, Object> markAllRead(@Valid @RequestBody(required = false) MarkAllBody body) {
        UUID userId = caller.requireUserId();
        List<NotificationCategory> categoryFilter = body == null ? null : body.category;
        OffsetDateTime olderThan = body == null ? null : body.olderThan;
        int updated = inbox.markAllRead(userId, categoryFilter, olderThan);
        return Map.of("updatedCount", updated);
    }

    private InboxService.ReadStateChange readChangeOf(PatchBody body) {
        if (body.readAtSet) {
            return body.readAt == null
                    ? InboxService.ReadStateChange.MARK_UNREAD
                    : InboxService.ReadStateChange.MARK_READ;
        }
        return InboxService.ReadStateChange.NONE;
    }

    private InboxService.DeleteStateChange deleteChangeOf(PatchBody body) {
        if (body.deletedAtSet) {
            return body.deletedAt == null
                    ? InboxService.DeleteStateChange.RESTORE
                    : InboxService.DeleteStateChange.SOFT_DELETE;
        }
        return InboxService.DeleteStateChange.NONE;
    }

    private static InboxService.ReadState readStateOf(String wire) {
        return switch (wire == null ? "all" : wire) {
            case "unread" -> InboxService.ReadState.UNREAD;
            case "read" -> InboxService.ReadState.READ;
            default -> InboxService.ReadState.ALL;
        };
    }

    private Map<String, Object> view(Notification n) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", n.getNotificationId());
        v.put("category", n.getCategory().name());
        v.put("priority", n.getPriority().name());
        v.put("titleKey", n.getTitleKey());
        v.put("bodySummaryKey", n.getBodySummaryKey());
        v.put("params", n.getParams());
        v.put("createdAt", n.getCreatedAt());
        v.put("readAt", n.getReadAt());
        v.put("deletedAt", n.getDeletedAt());
        v.put("deepLink", n.getDeepLink());
        v.put("actionHint", n.getActionHint().wire());
        v.put("locked", n.isLocked());
        if (n.getSupportReference() != null) v.put("supportReference", n.getSupportReference());
        if (n.getRelatedPaymentId() != null) v.put("relatedPaymentId", n.getRelatedPaymentId());
        if (n.getRelatedR2pRequestId() != null) v.put("relatedR2pRequestId", n.getRelatedR2pRequestId());
        if (n.getRelatedConsentId() != null) v.put("relatedConsentId", n.getRelatedConsentId());
        if (n.getRelatedMandateId() != null) v.put("relatedMandateId", n.getRelatedMandateId());
        if (n.getRelatedGdprRequestId() != null) v.put("relatedGdprRequestId", n.getRelatedGdprRequestId());
        return v;
    }

    public static final class PatchBody {
        public OffsetDateTime readAt;
        public OffsetDateTime deletedAt;
        // Tracks whether the JSON had the field at all (vs the field being
        // explicitly null which means clear).
        public boolean readAtSet;
        public boolean deletedAtSet;

        @com.fasterxml.jackson.annotation.JsonProperty("readAt")
        public void setReadAt(OffsetDateTime v) { this.readAt = v; this.readAtSet = true; }
        @com.fasterxml.jackson.annotation.JsonProperty("deletedAt")
        public void setDeletedAt(OffsetDateTime v) { this.deletedAt = v; this.deletedAtSet = true; }
    }

    public static final class MarkAllBody {
        public List<NotificationCategory> category;
        public OffsetDateTime olderThan;
    }
}
