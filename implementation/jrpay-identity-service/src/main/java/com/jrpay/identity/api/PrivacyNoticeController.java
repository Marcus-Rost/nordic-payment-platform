package com.jrpay.identity.api;

import com.jrpay.identity.config.AuthenticatedUser;
import com.jrpay.identity.domain.PrivacyAcceptance;
import com.jrpay.identity.privacy.PrivacyNoticeProperties;
import com.jrpay.identity.repository.PrivacyAcceptanceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Configuration
@EnableConfigurationProperties(PrivacyNoticeProperties.class)
@RequestMapping("/api/v1")
public class PrivacyNoticeController {

    private final PrivacyNoticeProperties notice;
    private final PrivacyAcceptanceRepository acceptances;
    private final AuthenticatedUser caller;

    public PrivacyNoticeController(PrivacyNoticeProperties notice,
                                   PrivacyAcceptanceRepository acceptances,
                                   AuthenticatedUser caller) {
        this.notice = notice;
        this.acceptances = acceptances;
        this.caller = caller;
    }

    @GetMapping("/public/privacy-notice/latest")
    public Map<String, Object> latest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", notice.currentVersion());
        body.put("publishedAt", notice.publishedAt());
        body.put("materialChange", notice.materialChange());
        body.put("locales", List.of("nb-NO", "en-US"));
        body.put("urls", Map.of("md", notice.mdUrl(), "pdf", notice.pdfUrl()));
        if (notice.changeSummaryKey() != null) {
            body.put("changeSummaryKey", notice.changeSummaryKey());
        }
        return body;
    }

    @GetMapping("/customer/privacy-notice/status")
    public Map<String, Object> status() {
        UUID userId = caller.requireUserId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentVersion", notice.currentVersion());
        var latest = acceptances.findLatestByUser(userId);
        String accepted = latest.map(PrivacyAcceptance::getNoticeVersion).orElse(null);
        body.put("userAcceptedVersion", accepted);
        body.put("materialChangeSinceAccepted",
                accepted == null ? true
                        : (notice.materialChange() && !accepted.equals(notice.currentVersion())));
        return body;
    }

    @PostMapping("/customer/privacy-notice/accept")
    public ResponseEntity<Map<String, Object>> accept(@Valid @RequestBody PrivacyAcceptBody body) {
        UUID userId = caller.requireUserId();
        if (!notice.currentVersion().equals(body.version())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "type", "urn:problem:invalid-input",
                    "title", "Version mismatch",
                    "status", 409,
                    "detail", "Provided version is not the current published version"
            ));
        }
        if (acceptances.findById(new PrivacyAcceptance.Pk(userId, body.version())).isEmpty()) {
            acceptances.save(new PrivacyAcceptance(userId, body.version()));
        }
        return ResponseEntity.noContent().build();
    }

    public record PrivacyAcceptBody(@NotBlank String version) { }
}
