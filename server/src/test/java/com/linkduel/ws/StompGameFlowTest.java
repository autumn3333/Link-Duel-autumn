package com.linkduel.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkduel.IntegrationTestSupport;
import com.linkduel.common.ErrorCode;
import com.linkduel.dto.Cell;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.GameRecord;
import com.linkduel.game.Match3Engine;
import com.linkduel.mapper.GameRecordMapper;
import com.linkduel.mapper.UserMapper;
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
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOMP 全流程集成测试(WebSocketStompClient 真实建连):
 * 登录 → 建连 → 匹配(match-found 事件)→ 订阅房间(快照,waiting→playing)
 * → 交换三消(双方实时收到 moved + cleared 连锁)→ 倒计时到期结算
 * (gameover 广播 + GAME_OVER 错误)→ 结算落库 → Redis 清理;
 * 另有对手断线 → 立即 FORFEIT 结算的用例。
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
    private UserMapper userMapper;

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

    private static Cell[] copyBoard(Cell[] src) {
        Cell[] out = new Cell[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = new Cell(src[i].getId(), src[i].getEmoji());
        }
        return out;
    }

    /** 用服务端同款引擎找第一个合法交换(保证测试必然命中) */
    private static int[] findValidSwap(Cell[] board) {
        for (int i = 0; i < board.length; i++) {
            int r = i / 8;
            int c = i % 8;
            if (c + 1 < 8 && Match3Engine.resolveSwap(
                    copyBoard(board), 8, i, i + 1, new Random(1)) != null) {
                return new int[]{i, i + 1};
            }
            if (r + 1 < 8 && Match3Engine.resolveSwap(
                    copyBoard(board), 8, i, i + 8, new Random(1)) != null) {
                return new int[]{i, i + 8};
            }
        }
        return null;
    }

    /** 排空一个 topic 队列里剩余的 cleared/reshuffled 连锁消息(步数不固定) */
    private static void drainCascade(QueueHandler topic) throws InterruptedException {
        Map<String, Object> next;
        while ((next = topic.pollOrNull(1)) != null
                && ("cleared".equals(next.get("type")) || "reshuffled".equals(next.get("type")))) {
            // 继续排空
        }
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
        Long aId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email='player_a@example.com'", Long.class);

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
        // 双方的房间订阅均已生效
        Thread.sleep(500);
        Map<String, Object> snapA = aSnap.poll(5);
        assertEquals("snapshot", snapA.get("type"));
        Map<String, Object> snapData = (Map<String, Object>) snapA.get("data");
        assertEquals("playing", snapData.get("status"));

        // A 订阅房间时双方在线标志已满足(waiting → playing),started 广播先于对局事件
        Map<String, Object> started = aTopic.poll(5);
        assertEquals("started", started.get("type"));

        Cell[] board = objectMapper.convertValue(snapData.get("board"), Cell[].class);
        int[] swap = findValidSwap(board);
        assertNotNull(swap, "生成的棋盘应至少有一个可行交换");

        // A 发交换 → 双方实时收到 moved(交换)+ cleared(每连锁步一条,含操作者)
        sessionA.send("/app/game/move", Map.of("from", swap[0], "to", swap[1]));

        Map<String, Object> movedA = aTopic.poll(5);
        assertEquals("moved", movedA.get("type"));
        Map<String, Object> movedData = (Map<String, Object>) movedA.get("data");
        assertEquals(swap[0], movedData.get("from"));
        assertEquals(swap[1], movedData.get("to"));
        assertEquals(aId.longValue(), ((Number) movedData.get("byUserId")).longValue());
        int scoreA = ((Number) movedData.get("scoreA")).intValue();
        assertTrue(scoreA >= 3, "一次合法交换至少消除 3 格");

        Map<String, Object> clearA = aTopic.poll(5);
        assertEquals("cleared", clearA.get("type"));
        Map<String, Object> clearData = (Map<String, Object>) clearA.get("data");
        assertTrue(!((List<?>) clearData.get("cells")).isEmpty());
        List<Cell> clearedBoard = objectMapper.convertValue(
                clearData.get("board"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Cell.class));
        assertEquals(64, clearedBoard.size());
        assertEquals(scoreA, ((Number) clearData.get("scoreA")).intValue());

        // 双方对称:B 也收到 moved + cleared(消除者自己也收到,用于播动画)
        assertEquals("moved", bTopic.poll(5).get("type"));
        assertEquals("cleared", bTopic.poll(5).get("type"));
        drainCascade(aTopic);
        drainCascade(bTopic);

        // 强制倒计时到期:B 再操作 → 先广播 gameover,再收到 GAME_OVER 错误
        RoomState room = matchmakingService.loadRoom(roomId);
        room.setDeadline(System.currentTimeMillis() - 1);
        matchmakingService.saveRoom(room);
        sessionB.send("/app/game/move", Map.of("from", 0, "to", 1));

        Map<String, Object> gameOver = aTopic.poll(5);
        assertEquals("gameover", gameOver.get("type"));
        assertEquals("timeout", ((Map<String, Object>) gameOver.get("data")).get("reason"));
        assertEquals("gameover", bTopic.poll(5).get("type"));

        Map<String, Object> err = bErr.poll(5);
        assertEquals("error", err.get("type"));
        assertEquals(40902, ((Map<String, Object>) err.get("data")).get("code")); // GAME_OVER

        // 结算落库:X:0 超时,A 胜
        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals("timeout", rec.getReason());
        assertEquals(aId, rec.getWinnerId());
        assertEquals(scoreA, rec.getScoreA());
        assertEquals(0, rec.getScoreB());

        // 房间相关 Redis key 已清理
        assertNull(matchmakingService.loadRoom(roomId));
    }

    @SuppressWarnings("unchecked")
    @Test
    void opponentDisconnectSettlesForfeitImmediately() throws Exception {
        String tokenA = login("player_a@example.com");
        String tokenB = login("player_b@example.com");
        Long aId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email='player_a@example.com'", Long.class);

        QueueHandler aMatch = new QueueHandler(), bMatch = new QueueHandler();
        QueueHandler aSnap = new QueueHandler();
        QueueHandler aTopic = new QueueHandler();

        sessionA = connect(tokenA);
        sessionB = connect(tokenB);
        sessionA.subscribe("/user/queue/match", aMatch);
        sessionB.subscribe("/user/queue/match", bMatch);
        sessionA.subscribe("/user/queue/snapshot", aSnap);
        Thread.sleep(500);

        JsonNode joinA = apiPost(tokenA, "/api/match/join", Map.of());
        assertEquals("queued", joinA.path("data").path("status").asText());
        JsonNode joinB = apiPost(tokenB, "/api/match/join", Map.of());
        String roomId = joinB.path("data").path("roomId").asText();
        assertEquals("matched", joinB.path("data").path("status").asText());

        aMatch.poll(5); // match-found

        // 只有 A 订阅房间:B 保持"匹配成功后尚未进入对局页"的状态(playing 已可开始)
        sessionA.subscribe("/topic/game/" + roomId, aTopic);
        Thread.sleep(500);
        Map<String, Object> snapA = aSnap.poll(5);
        assertEquals("playing", ((Map<String, Object>) snapA.get("data")).get("status"));
        Map<String, Object> started = aTopic.poll(5);
        assertEquals("started", started.get("type"));

        // B 断线 → 立即 FORFEIT 结算:在线方 A 胜 +3,gameover 广播到房间
        sessionB.disconnect();
        sessionB = null;

        Map<String, Object> gameOver = aTopic.poll(5);
        assertEquals("gameover", gameOver.get("type"));
        Map<String, Object> data = (Map<String, Object>) gameOver.get("data");
        assertEquals("forfeit", data.get("reason"));
        assertEquals(aId.longValue(), ((Number) data.get("winnerId")).longValue());

        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals("forfeit", rec.getStatus());
        assertEquals(aId, rec.getWinnerId());
        assertEquals(3, userMapper.findById(aId).getPoints());
        assertNull(matchmakingService.loadRoom(roomId));
    }
}
