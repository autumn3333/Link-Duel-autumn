package com.linkduel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.linkduel.mapper")
public class LinkDuelApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkDuelApplication.class, args);
    }
}
