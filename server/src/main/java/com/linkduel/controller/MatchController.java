package com.linkduel.controller;

import com.linkduel.common.Result;
import com.linkduel.dto.JoinResult;
import com.linkduel.entity.User;
import com.linkduel.security.AuthInterceptor;
import com.linkduel.service.MatchmakingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/match")
@RequiredArgsConstructor
public class MatchController {

    private final MatchmakingService matchmakingService;

    @PostMapping("/join")
    public Result<JoinResult> join(@RequestAttribute(AuthInterceptor.ATTR_CURRENT_USER) User currentUser) {
        return Result.ok(matchmakingService.join(currentUser));
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestAttribute(AuthInterceptor.ATTR_CURRENT_USER) User currentUser) {
        matchmakingService.cancel(currentUser);
        return Result.ok();
    }
}
