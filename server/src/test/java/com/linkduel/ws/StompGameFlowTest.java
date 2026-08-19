package com.linkduel.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkduel.IntegrationTestSupport;
import com.linkduel.common.ErrorCode;
import com.linkduel.dto.Cell;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.GameRecord;
import com.linkduel.game.PathValidator;
import com.linkduel.mapper.GameRecordMapper;
import com.linkduel.service.MatchmakingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOMP 全流程集成测试(WebSocketStompClient 真实建连):
 * 登录 → 建连 → 匹配(match-found 事件)→ 订阅房间(快照,waiting→playing)
 * → 消除(双方实时收到 eliminated + 路径)→ 倒计时到期结算(gameover 广播 + GAME_OVER 错误)
 * → 结算落库 → Redis 清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.data.redis.database=15")
class StompGameFlowTest extends IntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MatchmakingService matchmakingService;

    @Autowired
    private GameRecordMapper gameRecordMapper;

    @Autowired
    private GameEventPublisher eventPublisher;

    @Autowired
    private SimpMessagingTemplate messaging;

    private StompSession sessionA;
    private StompSession sessionB;

    /** 用阻塞队列收集某个订阅目的地收到的所有信封 */
    private static class QueueHandler implements StompFrameHandler {
        final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.add((Map<String, Object>) payload);
        }

        Map<String, Object> poll(int seconds) throws InterruptedException {
            Map<String, Object> frame = frames.poll(seconds, TimeUnit.SECONDS);
            assertNotNull(frame, "等待 STOMP 消息超时");
            return frame;
        }

        /** 探针用:允许为空,不抛断言 */
        Map<String, Object> pollOrNull(int seconds) throws InterruptedException {
            return frames.poll(seconds, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void disconnect() {
        if (sessionA != null && sessionA.isConnected()) {
            sessionA.disconnect();
        }
        if (sessionB != null && sessionB.isConnected()) {
            sessionB.disconnect();
        }
        // 复位种子账号统计并删除本用例写入的对局记录,保持排行榜初始状态
        jdbc.update("UPDATE users SET points=0, wins=0, losses=0, draws=0"
                + " WHERE email IN ('player_a@example.com','player_b@example.com')");
        jdbc.update("DELETE FROM game_records WHERE player_a_id IN"
                + " (SELECT id FROM users WHERE email IN ('player_a@example.com','player_b@example.com'))"
                + " OR player_b_id IN (SELECT id FROM users WHERE email IN ('player_a@example.com','player_b@example.com'))");
    }

    private String login(String email) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", "Test123456!"), headers), String.class);
        JsonNode node = objectMapper.readTree(resp.getBody());
        assertEquals(0, node.path("code").asInt());
        return node.path("data").path("token").asText();
    }

    private JsonNode apiPost(String token, String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.postForEntity(
                "http://localhost:" + port + path, new HttpEntity<>(body, headers), String.class);
        return objectMapper.readTree(resp.getBody());
    }

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // 必须显式配 JSON 转换器:默认 StringMessageConverter 会把服务端的
        // JSON 信封转成 String,与 QueueHandler 期望的 Map 类型不匹配导致消息被丢弃
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connect("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);
    }

    /** 用服务端同款算法找第一个合法消除对(保证测试必然命中) */
    private int[] findLegalMove(Cell[] board) {
        for (int i = 0; i < board.length; i++) {
            for (int j = i + 1; j < board.length; j++) {
                if (PathValidator.findPath(board, 8, i, j) != null) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Test
    void userQueueRoutingAndBadSubscribeRejection() throws Exception {
        String tokenA = login("player_a@example.com");
        Long aId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email='player_a@example.com'", Long.class);

        sessionA = connect(tokenA);

        // 点对点路由:发布到 /user/{id}/queue/match,客户端应能收到
        QueueHandler aMatch = new QueueHandler();
        sessionA.subscribe("/user/queue/match", aMatch);
        Thread.sleep(300);
        eventPublisher.toUser(aId, "match", "probe", Map.of("k", "v"));
        Map<String, Object> userFrame = aMatch.pollOrNull(3);
        assertNotNull(userFrame, "点对点 /user/queue 路由失败");
        assertEquals("probe", userFrame.get("type"));

        // 普通 topic 广播(不经房间校验)
        QueueHandler aTopic = new QueueHandler();
        sessionA.subscribe("/topic/probe", aTopic);
        Thread.sleep(300);
        messaging.convertAndSend("/topic/probe", Map.of("type", "probe"));
        Map<String, Object> topicFrame = aTopic.pollOrNull(3);
        assertNotNull(topicFrame, "topic 广播路由失败");
        assertEquals("probe", topicFrame.get("type"));

        // 订阅不存在的房间:应只拒绝订阅 + 回 GAME_OVER 错误,不能断开整个连接
        // (曾经抛 BizException 穿透到 StompSubProtocolHandler,直接把 WS 连接杀掉)
        QueueHandler aErr = new QueueHandler();
        sessionA.subscribe("/user/queue/errors", aErr);
        Thread.sleep(300);
        sessionA.subscribe("/topic/game/probe-room", new QueueHandler());
        Map<String, Object> err = aErr.poll(3);
        assertEquals("error", err.get("type"));
        assertEquals(ErrorCode.GAME_OVER.getCode(),
                ((Map<String, Object>) err.get("data")).get("code"));
        assertTrue(sessionA.isConnected(), "非法房间订阅不应断开连接");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fullMatchFlowOverStomp() throws Exception {
        String tokenA = login("player_a@example.com");
        String tokenB = login("player_b@example.com");

        QueueHandler aMatch = new QueueHandler(), bMatch = new QueueHandler();
        QueueHandler aSnap = new QueueHandler(), bSnap = new QueueHandler();
        QueueHandler aTopic = new QueueHandler(), bTopic = new QueueHandler();
        QueueHandler aErr = new QueueHandler(), bErr = new QueueHandler();

        sessionA = connect(tokenA);
        sessionB = connect(tokenB);
        sessionA.subscribe("/user/queue/match", aMatch);
        sessionB.subscribe("/user/queue/match", bMatch);
        sessionA.subscribe("/user/queue/snapshot", aSnap);
        sessionB.subscribe("/user/queue/snapshot", bSnap);
        sessionA.subscribe("/user/queue/errors", aErr);
        sessionB.subscribe("/user/queue/errors", bErr);

        // SUBSCRIBE 是异步帧,短暂等待全部在服务端注册后再入队,
        // 否则 match-found 事件可能先于订阅注册到达而被静默丢弃。
        // (Spring 6 已移除 receipt/autoReceipt 机制,测试侧只能等待;
        //  生产不受影响:前端在大厅建连时就完成订阅,远早于匹配)
        Thread.sleep(500);

        // A 先入队,B 后入队触发配对
        JsonNode joinA = apiPost(tokenA, "/api/match/join", Map.of());
        assertEquals("queued", joinA.path("data").path("status").asText());
        JsonNode joinB = apiPost(tokenB, "/api/match/join", Map.of());
        assertEquals("matched", joinB.path("data").path("status").asText());
        String roomId = joinB.path("data").path("roomId").asText();

        // 先入队的 A 通过 STOMP 事件得知配对成功
        Map<String, Object> found = aMatch.poll(5);
        assertEquals("match-found", found.get("type"));
        assertEquals(roomId, ((Map<String, Object>) found.get("data")).get("roomId"));

        // 双方订阅房间主题 → 收到 playing 快照(订阅即恢复)
        sessionA.subscribe("/topic/game/" + roomId, aTopic);
        sessionB.subscribe("/topic/game/" + roomId, bTopic);
        // 同上:等待双方的房间订阅在服务端注册就绪,确保 A 发 move 前
        // 双方的 eliminated 订阅均已生效
        Thread.sleep(500);
        Map<String, Object> snapA = aSnap.poll(5);
        assertEquals("snapshot", snapA.get("type"));
        Map<String, Object> snapData = (Map<String, Object>) snapA.get("data");
        assertEquals("playing", snapData.get("status"));

        Cell[] board = objectMapper.convertValue(snapData.get("board"), Cell[].class);
        int[] move = findLegalMove(board);
        assertNotNull(move, "生成的棋盘应至少有一个可消除对");

        // A 发消除 → 双方实时收到 eliminated(消除者自己也收到,用于播动画)
        sessionA.send("/app/game/move", Map.of("cellA", move[0], "cellB", move[1]));
        Map<String, Object> elimA = aTopic.poll(5);
        assertEquals("eliminated", elimA.get("type"));
        Map<String, Object> elimB = bTopic.poll(5);
        assertEquals("eliminated", elimB.get("type"));
        Map<String, Object> elimData = (Map<String, Object>) elimB.get("data");
        assertEquals(move[0], elimData.get("cellA"));
        assertEquals(move[1], elimData.get("cellB"));
        assertFalse(((List<?>) elimData.get("path")).isEmpty());
        assertEquals(1, elimData.get("scoreA"));

        // 强制倒计时到期:B 再消除 → 先广播 gameover,再收到 GAME_OVER 错误
        RoomState room = matchmakingService.loadRoom(roomId);
        room.setDeadline(System.currentTimeMillis() - 1);
        matchmakingService.saveRoom(room);
        board[move[0]].setEliminated(true);
        board[move[1]].setEliminated(true);
        int[] move2 = findLegalMove(board);
        assertNotNull(move2);
        sessionB.send("/app/game/move", Map.of("cellA", move2[0], "cellB", move2[1]));

        Map<String, Object> gameOver = aTopic.poll(5);
        assertEquals("gameover", gameOver.get("type"));
        assertEquals("timeout", ((Map<String, Object>) gameOver.get("data")).get("reason"));

        Map<String, Object> err = bErr.poll(5);
        assertEquals("error", err.get("type"));
        assertEquals(40902, ((Map<String, Object>) err.get("data")).get("code")); // GAME_OVER

        // 结算落库:1:0 超时,A 胜
        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals("timeout", rec.getReason());
        assertEquals(1, rec.getScoreA());
        assertEquals(0, rec.getScoreB());

        // 房间相关 Redis key 已清理
        assertNull(matchmakingService.loadRoom(roomId));
    }
}
