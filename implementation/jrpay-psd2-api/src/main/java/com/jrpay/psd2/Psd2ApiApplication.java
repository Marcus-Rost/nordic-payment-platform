package com.jrpay.psd2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class Psd2ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(Psd2ApiApplication.class, args);
    }
}
