package com.gb.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// CLAUDE.md §2: common의 GlobalExceptionHandler(com.gb.common.*)가 스캔되도록 base 확장.
// 또한 R1 PoC 단계에서는 DB 연결이 필요 없어 DataSourceAutoConfiguration을 일시 제외한다.
//   → Phase 2(엔티티 추가) 진입 시 exclude 제거.
@SpringBootApplication(
        scanBasePackages = "com.gb",
        exclude = { DataSourceAutoConfiguration.class }
)
public class DocumentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }

}
