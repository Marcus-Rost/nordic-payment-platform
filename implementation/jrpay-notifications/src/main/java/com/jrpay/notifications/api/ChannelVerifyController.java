package com.jrpay.notifications.api;

import com.jrpay.notifications.config.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verification re-send endpoints (UC-G21 AF-01 / AF-02). The actual change of
 * email/phone happens in {@code customer-profile.yaml} (UC-G22); these
 * endpoints only re-trigger an outbound verification message for a channel
 * the user already has on file.
 *
 * <p>Portfolio default logs the dispatch — the production channel adapter
 * (SES / Postmark / Twilio) is wired in via the same SPI as outbound
 * notifications.
 */
@RestController
@RequestMapping("/api/v1/customer/notifications")
public class ChannelVerifyController {

    private static final Logger log = LoggerFactory.getLogger(ChannelVerifyController.class);

    private final AuthenticatedUser caller;

    public ChannelVerifyController(AuthenticatedUser caller) {
        this.caller = caller;
    }

    @PostMapping("/email/verify")
    public ResponseEntity<Void> resendEmail() {
        log.info("Re-sending email verification for user={}", caller.requireUserId());
        // Rate-limit + actual dispatch belong to a future enhancement; the
        // controller is wired so customer GUIs can integrate now.
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/phone/verify")
    public ResponseEntity<Void> resendPhone() {
        log.info("Re-sending phone OTP for user={}", caller.requireUserId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
