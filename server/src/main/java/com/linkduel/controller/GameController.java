package com.linkduel.controller;

import com.linkduel.common.Result;
import com.linkduel.dto.CurrentGameResponse;
import com.linkduel.entity.User;
import com.linkduel.security.AuthInterceptor;
import com.linkduel.service.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameController {

    private final StringRedisTemplate redis;

    /** 断线重连/页面刷新后的对局发现:返回 roomId 或 null */
    @GetMapping("/current")
    public Result<CurrentGameResponse> current(
            @RequestAttribute(AuthInterceptor.ATTR_CURRENT_USER) User currentUser) {
        String roomId = redis.opsForValue().get(RedisKeys.userGame(currentUser.getId()));
        return Result.ok(new CurrentGameResponse(roomId));
    }
}
