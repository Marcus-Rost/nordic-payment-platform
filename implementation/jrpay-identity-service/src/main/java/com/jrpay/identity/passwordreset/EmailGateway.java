package com.jrpay.identity.passwordreset;

import com.jrpay.identity.domain.IdentityUser;
import java.time.OffsetDateTime;

/**
 * Outbound-email seam. The portfolio default logs the dispatch with the
 * token-hash so the test harness can pick it up; production swaps this for
 * a notification-service adapter (Phase 2G).
 */
public interface EmailGateway {

    void dispatchPasswordResetLink(String emailOrUsername,
                                   IdentityUser.Audience audience,
                                   String oneTimeToken,
                                   OffsetDateTime expiresAt);
}
