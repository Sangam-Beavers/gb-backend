package com.gb.appadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.gb")
public class AppAdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppAdminServiceApplication.class, args);
    }
}
