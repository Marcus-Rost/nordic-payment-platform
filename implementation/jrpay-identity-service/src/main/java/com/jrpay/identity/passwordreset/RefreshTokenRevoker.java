package com.jrpay.identity.passwordreset;

import java.util.UUID;

/**
 * Seam for revoking every active refresh token associated with a user. The
 * Spring Authorization Server stores them in {@code identity_oauth_authorizations};
 * this seam lets the password-reset path invalidate them without coupling the
 * service to the SAS internals directly.
 */
public interface RefreshTokenRevoker {

    void revokeAllForUser(UUID userId);
}
