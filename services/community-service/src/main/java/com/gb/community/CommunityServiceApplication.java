package com.gb.community;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// CLAUDE.md §2: common 모듈(com.gb.common.*)이 스캔되도록 base 확장.
@SpringBootApplication(scanBasePackages = "com.gb")
public class CommunityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityServiceApplication.class, args);
    }
}
