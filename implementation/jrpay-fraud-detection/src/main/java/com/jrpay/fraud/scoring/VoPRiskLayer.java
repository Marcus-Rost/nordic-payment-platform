package com.jrpay.fraud.scoring;

import com.jrpay.domain.payment.VoPResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Layer 3: VoP risk adjustment.
 * Adjusts fraud score based on VoP result and payer override behavior.
 * Per INTERFACE_CONTRACTS Section 1.2 and VOP-I01 through VOP-I03.
 */
@Component
@Order(3)
public class VoPRiskLayer implements ScoringLayer {

    @Override
    public String name() {
        return "VOP_RISK";
    }

    @Override
    public LayerResult score(ScoringRequest request) {
        if (request.vopResult() == null) {
            return LayerResult.continueWith(0.0, "NO_VOP_DATA");
        }

        double score = 0.0;
        String detail = request.vopResult().name();

        if (request.vopPayerOverride()) {
            switch (request.vopResult()) {
                case NO_MATCH -> {
                    score = 0.3; // VOP-I02: significant APP fraud indicator
                    detail = "NO_MATCH+OVERRIDE(+0.3)";
                }
                case NOT_FOUND -> {
                    score = 0.4; // VOP-I02: IBAN doesn't exist, high risk
                    detail = "NOT_FOUND+OVERRIDE(+0.4)";
                }
                case CLOSE_MATCH -> {
                    score = 0.1; // VOP-I03: payer ignored correction
                    detail = "CLOSE_MATCH+IGNORED(+0.1)";
                }
                default -> detail = request.vopResult().name() + "+OVERRIDE";
            }
        }

        return LayerResult.continueWith(score, detail);
    }
}
