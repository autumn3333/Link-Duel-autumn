package com.linkduel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkduel.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 登录鉴权集成测试:种子账号登录、密码校验、JWT 拦截。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.data.redis.database=15")
class AuthIntegrationTest extends IntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
        return objectMapper.readTree(resp.getBody());
    }

    @Test
    void seedAccountCanLogin() throws Exception {
        JsonNode resp = postJson("/api/auth/login",
                Map.of("email", "player_a@example.com", "password", "Test123456!"));
        assertEquals(0, resp.path("code").asInt());
        assertFalse(resp.path("data").path("token").asText().isBlank());
        assertEquals("player_a@example.com", resp.path("data").path("user").path("email").asText());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        JsonNode resp = postJson("/api/auth/login",
                Map.of("email", "player_a@example.com", "password", "wrong-password"));
        assertEquals(40001, resp.path("code").asInt());
    }

    @Test
    void meWithoutTokenIsRejected() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/users/me"), String.class);
        assertEquals(40100, objectMapper.readTree(resp.getBody()).path("code").asInt());
    }

    @Test
    void meWithTokenReturnsCurrentUser() throws Exception {
        JsonNode login = postJson("/api/auth/login",
                Map.of("email", "player_b@example.com", "password", "Test123456!"));
        String token = login.path("data").path("token").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange(url("/api/users/me"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        JsonNode node = objectMapper.readTree(resp.getBody());
        assertEquals(0, node.path("code").asInt());
        assertEquals("player_b@example.com", node.path("data").path("email").asText());
    }
}
