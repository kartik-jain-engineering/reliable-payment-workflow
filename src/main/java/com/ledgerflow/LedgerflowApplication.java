package com.ledgerflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


@SpringBootApplication
@ConfigurationPropertiesScan
public class LedgerflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerflowApplication.class, args);
    }
}
