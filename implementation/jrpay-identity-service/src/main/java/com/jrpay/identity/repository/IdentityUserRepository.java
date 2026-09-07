package com.jrpay.identity.repository;

import com.jrpay.identity.domain.IdentityUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityUserRepository extends JpaRepository<IdentityUser, UUID> {

    Optional<IdentityUser> findByAudienceAndUsername(IdentityUser.Audience audience, String username);

    Optional<IdentityUser> findByEmail(String email);
}
