package com.jrpay.starter.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the RFC 9457 error handler for all services.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ErrorHandlerAutoConfiguration {

    @Bean
    public PaymentExceptionHandler paymentExceptionHandler() {
        return new PaymentExceptionHandler();
    }
}
