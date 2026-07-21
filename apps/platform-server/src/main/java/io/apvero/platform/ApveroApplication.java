package io.apvero.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApveroApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApveroApplication.class, args);
    }
}
