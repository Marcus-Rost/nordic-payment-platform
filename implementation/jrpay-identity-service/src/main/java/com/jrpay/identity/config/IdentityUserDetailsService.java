package com.jrpay.identity.config;

import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.repository.IdentityUserRepository;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves users for the OAuth2 authorize flow's form login. The form posts
 * {@code username + password}; we look up first by email then by username
 * (audience-agnostic) and surface the role as an authority.
 *
 * <p>Customers do not have a role; their authority is {@code ROLE_CUSTOMER}.
 * Operators have one of the {@link com.jrpay.identity.domain.IdentityUser}
 * role values mirrored to {@code ROLE_<NAME>}.
 */
@Service
public class IdentityUserDetailsService implements UserDetailsService {

    private final IdentityUserRepository users;

    public IdentityUserDetailsService(IdentityUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        IdentityUser user = users.findByEmail(username)
                .or(() -> users.findByAudienceAndUsername(IdentityUser.Audience.customer, username))
                .or(() -> users.findByAudienceAndUsername(IdentityUser.Audience.admin, username))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        if (user.getStatus() == IdentityUser.UserStatus.DELETED
                || user.getStatus() == IdentityUser.UserStatus.SUSPENDED) {
            throw new UsernameNotFoundException("Account not active");
        }

        String role = user.getRole() == null
                ? "CUSTOMER"
                : user.getRole();
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + role)))
                .accountLocked(user.isLockedNow())
                .disabled(user.getStatus() != IdentityUser.UserStatus.ACTIVE
                        && user.getStatus() != IdentityUser.UserStatus.PENDING_FIRST_LOGIN)
                .build();
    }
}
