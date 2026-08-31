package com.mineguard;

import com.mineguard.config.MineGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(MineGuardProperties.class)
public class MineGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(MineGuardApplication.class, args);
    }
}
