package com.gb.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// CLAUDE.md §2: common의 GlobalExceptionHandler(com.gb.common.*)가 스캔되도록 base 확장.
@SpringBootApplication(scanBasePackages = "com.gb")
@ConfigurationPropertiesScan(basePackages = "com.gb.document")
@EnableScheduling // StaleSubmissionSweeper(ANALYZING 고아 건 정리) 활성화
public class DocumentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }

}
