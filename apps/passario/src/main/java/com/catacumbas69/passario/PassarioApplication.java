package com.catacumbas69.passario;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PassarioApplication {
    public static void main(String[] args) {
        SpringApplication.run(PassarioApplication.class, args);
    }
}
