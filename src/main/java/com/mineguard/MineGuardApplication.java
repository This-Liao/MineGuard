package com.mineguard;

import com.mineguard.config.MineGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MineGuardProperties.class)
public class MineGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(MineGuardApplication.class, args);
    }
}
