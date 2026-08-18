package com.linkduel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 仅注册 BCryptPasswordEncoder 一个组件,不引入 Spring Security 的过滤器链。
 */
@Configuration
public class BeanConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
