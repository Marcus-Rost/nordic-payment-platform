package com.jrpay.notifications.repository;

import com.jrpay.notifications.domain.Notification;
import com.jrpay.notifications.domain.NotificationCategory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    /**
     * Far-past sentinel used when the caller doesn't supply a {@code from}
     * bound. We avoid binding null timestamps because Postgres can't infer
     * the parameter type when the first occurrence in the SQL is in an
     * {@code IS NULL} check.
     */
    OffsetDateTime FAR_PAST = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    OffsetDateTime FAR_FUTURE = OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);

    @Query("""
            select n from Notification n
            where n.userId = :userId
              and (:includeDeleted = true or n.deletedAt is null)
              and (:onlyUnread = false or n.readAt is null)
              and (:onlyRead = false or n.readAt is not null)
              and n.createdAt >= :from
              and n.createdAt <= :to
              and n.createdAt > :since
            """)
    Page<Notification> searchAllCategories(
            @Param("userId") UUID userId,
            @Param("onlyUnread") boolean onlyUnread,
            @Param("onlyRead") boolean onlyRead,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("since") OffsetDateTime since,
            @Param("includeDeleted") boolean includeDeleted,
            Pageable pageable);

    @Query("""
            select n from Notification n
            where n.userId = :userId
              and n.category in (:categories)
              and (:includeDeleted = true or n.deletedAt is null)
              and (:onlyUnread = false or n.readAt is null)
              and (:onlyRead = false or n.readAt is not null)
              and n.createdAt >= :from
              and n.createdAt <= :to
              and n.createdAt > :since
            """)
    Page<Notification> searchByCategories(
            @Param("userId") UUID userId,
            @Param("categories") List<NotificationCategory> categories,
            @Param("onlyUnread") boolean onlyUnread,
            @Param("onlyRead") boolean onlyRead,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("since") OffsetDateTime since,
            @Param("includeDeleted") boolean includeDeleted,
            Pageable pageable);

    /**
     * Postgres rejects {@code IN (?)} with a null list (parameter type
     * undeterminable), so we route by filter presence at the JPQL level,
     * and substitute sentinel timestamps for missing date bounds.
     */
    default Page<Notification> search(UUID userId,
                                      List<NotificationCategory> categoryFilter,
                                      boolean onlyUnread,
                                      boolean onlyRead,
                                      OffsetDateTime from,
                                      OffsetDateTime to,
                                      OffsetDateTime since,
                                      boolean includeDeleted,
                                      Pageable pageable) {
        OffsetDateTime fromBound = from == null ? FAR_PAST : from;
        OffsetDateTime toBound = to == null ? FAR_FUTURE : to;
        // `since` semantics: strictly-greater-than. Default sentinel = far past,
        // which trivially passes for every row.
        OffsetDateTime sinceBound = since == null ? FAR_PAST : since;
        if (categoryFilter == null || categoryFilter.isEmpty()) {
            return searchAllCategories(userId, onlyUnread, onlyRead,
                    fromBound, toBound, sinceBound, includeDeleted, pageable);
        }
        return searchByCategories(userId, categoryFilter, onlyUnread, onlyRead,
                fromBound, toBound, sinceBound, includeDeleted, pageable);
    }

    long countByUserIdAndReadAtIsNullAndDeletedAtIsNull(UUID userId);

    @Modifying
    @Query("""
            update Notification n
            set n.readAt = :now
            where n.userId = :userId
              and n.readAt is null
              and n.deletedAt is null
              and n.createdAt <= :olderThan
            """)
    int markAllReadForUserAllCategories(@Param("userId") UUID userId,
                                        @Param("now") OffsetDateTime now,
                                        @Param("olderThan") OffsetDateTime olderThan);

    @Modifying
    @Query("""
            update Notification n
            set n.readAt = :now
            where n.userId = :userId
              and n.readAt is null
              and n.deletedAt is null
              and n.category in (:categories)
              and n.createdAt <= :olderThan
            """)
    int markAllReadForUserByCategories(@Param("userId") UUID userId,
                                       @Param("now") OffsetDateTime now,
                                       @Param("olderThan") OffsetDateTime olderThan,
                                       @Param("categories") List<NotificationCategory> categories);

    default int markAllReadForUser(UUID userId,
                                   OffsetDateTime now,
                                   OffsetDateTime olderThan,
                                   List<NotificationCategory> categoryFilter) {
        OffsetDateTime cutoff = olderThan == null ? FAR_FUTURE : olderThan;
        if (categoryFilter == null || categoryFilter.isEmpty()) {
            return markAllReadForUserAllCategories(userId, now, cutoff);
        }
        return markAllReadForUserByCategories(userId, now, cutoff, categoryFilter);
    }
}
