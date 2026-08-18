package com.linkduel.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkduel.common.ErrorCode;
import com.linkduel.common.Result;
import com.linkduel.entity.User;
import com.linkduel.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 轻量 JWT 鉴权拦截器:校验 Authorization 头,把当前用户放入 request 属性。
 * 不通过则直接以 JSON 返回 40100,不进入 Controller。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_CURRENT_USER = "currentUser";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, ErrorCode.UNAUTHORIZED);
        }
        Long userId = jwtUtil.parseUserId(header.substring(7));
        if (userId == null) {
            return reject(response, ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            return reject(response, ErrorCode.UNAUTHORIZED);
        }
        request.setAttribute(ATTR_CURRENT_USER, user);
        return true;
    }

    private boolean reject(HttpServletResponse response, ErrorCode errorCode) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(errorCode)));
        return false;
    }
}
