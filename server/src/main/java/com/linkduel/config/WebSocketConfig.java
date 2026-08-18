package com.linkduel.config;

import com.linkduel.ws.AuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket 配置。
 * 客户端连接 ws://host:8080/ws,发消息到 /app/** ,订阅 /topic/** 与 /user/queue/**。
 *
 * <p>注意:SimpleBroker 的心跳必须配置 TaskScheduler,否则心跳静默失效,
 * 网络中断时双方都无法及时发现。
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthChannelInterceptor authChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(taskScheduler());
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 原生 WebSocket,不用 SockJS;前端经 Vite 代理时 WS 无同源限制
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }

    /**
     * 共享调度器,bean 名必须是 taskScheduler:同名时 Spring Boot 不再自建默认
     * TaskScheduler,否则容器里出现两个 TaskScheduler,@Scheduled 与按类型注入都会冲突。
     * 同时服务 STOMP 心跳(10s)、延迟快照推送与 GameSweeper 扫描。
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("task-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
