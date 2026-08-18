package com.linkduel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    /**
     * 匹配 Lua 脚本(classpath:lua/match.lua),返回配对成功的两个 userId。
     */
    @Bean
    public DefaultRedisScript<List> matchScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/match.lua"));
        script.setResultType(List.class);
        return script;
    }
}
