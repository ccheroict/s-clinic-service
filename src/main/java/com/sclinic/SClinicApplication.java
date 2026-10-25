package com.sclinic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for the nightly security housekeeping pass. It assumes a
 * single application instance; see {@code SecurityHousekeepingJob}.
 */
@SpringBootApplication
@EnableScheduling
public class SClinicApplication {

    public static void main(String[] args) {
        SpringApplication.run(SClinicApplication.class, args);
    }
}
