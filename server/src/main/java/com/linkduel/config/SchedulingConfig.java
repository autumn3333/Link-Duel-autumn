package com.linkduel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 共享调度器。bean 名必须是 taskScheduler:同名时 Spring Boot 不再自建默认
 * TaskScheduler,否则容器里出现两个 TaskScheduler,@Scheduled 与按类型注入都会冲突。
 * 同时服务 STOMP 心跳(10s)、延迟快照推送与 GameSweeper 扫描。
 *
 * <p>单独成类而非放在 WebSocketConfig 里:WebSocketConfig 依赖 AuthChannelInterceptor,
 * 而后者构造器又需要 TaskScheduler——放同一类会形成构造器循环依赖,应用无法启动。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("task-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
