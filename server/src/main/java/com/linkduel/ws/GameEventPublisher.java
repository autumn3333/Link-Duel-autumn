package com.linkduel.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 对局事件发布器。
 * 所有实时消息统一走事件信封 {"type": ..., "serverNow": ..., "data": {...}}。
 * STOMP broker 就绪前(集成测试等场景)安全降级为仅打日志。
 */
@Slf4j
@Component
public class GameEventPublisher {

    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;

    public GameEventPublisher(ObjectProvider<SimpMessagingTemplate> messagingProvider) {
        this.messagingProvider = messagingProvider;
    }

    /** 广播到房间主题 /topic/game/{roomId} */
    public void toRoom(String roomId, String type, Map<String, Object> data) {
        publish("/topic/game/" + roomId, type, data);
    }

    /** 点对点发送到用户队列 /user/queue/{queue} */
    public void toUser(Long userId, String queue, String type, Map<String, Object> data) {
        publish("/user/" + userId + "/queue/" + queue, type, data);
    }

    private void publish(String destination, String type, Map<String, Object> data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("serverNow", System.currentTimeMillis());
        envelope.put("data", data == null ? Map.of() : data);
        SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
        if (messaging != null) {
            messaging.convertAndSend(destination, envelope);
        } else {
            log.debug("STOMP broker 未就绪,事件丢弃: {} {}", destination, type);
        }
    }
}
