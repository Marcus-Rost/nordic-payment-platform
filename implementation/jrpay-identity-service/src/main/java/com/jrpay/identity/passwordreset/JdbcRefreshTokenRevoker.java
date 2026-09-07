package com.jrpay.identity.passwordreset;

import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Default {@link RefreshTokenRevoker}: clears refresh / access / id-token
 * columns on every authorization row referencing this user. The user-id
 * is matched against {@code principal_name} (Spring Authorization Server's
 * convention) — the form-login principal name is the username, which we
 * also map back to the user id at session-creation time.
 *
 * <p>For the portfolio default this is a soft revoke (column nulling) rather
 * than a row delete so the audit-query service can still see the
 * authorization row that issued the token.
 */
@Component
public class JdbcRefreshTokenRevoker implements RefreshTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(JdbcRefreshTokenRevoker.class);

    private final JdbcTemplate jdbc;

    public JdbcRefreshTokenRevoker(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void revokeAllForUser(UUID userId) {
        // Look up the user's username so we can match by principal_name. The
        // SAS table is keyed on principal_name, not on our internal user_id.
        String username = jdbc.query(
                "SELECT username FROM identity_users WHERE user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                userId);
        if (username == null) return;
        int rows = jdbc.update(
                "UPDATE identity_oauth_authorizations SET "
                        + "refresh_token_value = NULL, "
                        + "access_token_value = NULL, "
                        + "oidc_id_token_value = NULL "
                        + "WHERE principal_name = ?",
                username);
        log.info("Revoked tokens on {} authorization rows for user {}", rows, userId);
    }
}
