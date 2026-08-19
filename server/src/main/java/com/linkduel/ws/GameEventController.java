package com.linkduel.ws;

import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.dto.MoveRequest;
import com.linkduel.service.GameService;
import com.linkduel.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * 对局相关 STOMP 消息入口(app 前缀由 WebSocketConfig 配置)。
 * <ul>
 *   <li>/app/heartbeat:心跳,刷新在线 TTL,回 serverNow 供客户端计算时钟偏移;</li>
 *   <li>/app/game/move:交换请求,权威校验在 GameService,失败通过 /user/queue/errors 回错误码。</li>
 * </ul>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GameEventController {

    private final PresenceService presenceService;
    private final GameService gameService;
    private final GameEventPublisher eventPublisher;

    @MessageMapping("/heartbeat")
    public void heartbeat(Principal principal, @Header("simpSessionId") String sessionId) {
        if (principal == null) {
            return;
        }
        Long userId = Long.valueOf(principal.getName());
        presenceService.setOnlineSession(userId, sessionId);
        eventPublisher.toUser(userId, "heartbeat", "heartbeat",
                Map.of("serverNow", System.currentTimeMillis()));
    }

    @MessageMapping("/game/move")
    public void move(Principal principal, @Payload(required = false) MoveRequest request) {
        if (principal == null) {
            return;
        }
        Long userId = Long.valueOf(principal.getName());
        if (request == null) {
            sendError(userId, ErrorCode.PARAM_ERROR);
            return;
        }
        try {
            gameService.move(userId, request.getFrom(), request.getTo());
        } catch (BizException e) {
            sendError(userId, e.getErrorCode());
        } catch (Exception e) {
            log.error("处理消除请求异常 userId={}", userId, e);
            sendError(userId, ErrorCode.INTERNAL_ERROR);
        }
    }

    private void sendError(Long userId, ErrorCode errorCode) {
        eventPublisher.toUser(userId, "errors", "error",
                Map.of("code", errorCode.getCode(), "message", errorCode.getDefaultMessage()));
    }
}
