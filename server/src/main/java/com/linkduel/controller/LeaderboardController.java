package com.linkduel.controller;

import com.linkduel.common.Result;
import com.linkduel.dto.LeaderboardVO;
import com.linkduel.entity.User;
import com.linkduel.security.AuthInterceptor;
import com.linkduel.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping
    public Result<List<LeaderboardVO>> top(
            @RequestParam(defaultValue = "10") int limit,
            @RequestAttribute(AuthInterceptor.ATTR_CURRENT_USER) User currentUser) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return Result.ok(leaderboardService.topN(safeLimit, currentUser.getId()));
    }
}
