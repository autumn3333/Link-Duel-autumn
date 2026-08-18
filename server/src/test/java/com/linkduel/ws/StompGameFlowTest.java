package com.linkduel.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkduel.IntegrationTestSupport;
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
    }

    @AfterEach
    void disconnect() {
        if (sessionA != null && sessionA.isConnected()) {
            sessionA.disconnect();
        }
        if (sessionB != null && sessionB.isConnected()) {
            sessionB.disconnect();
        }
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
