package com.linkduel.service;

import com.linkduel.dto.LeaderboardVO;
import com.linkduel.entity.User;
import com.linkduel.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实时排行榜:Redis ZSET(leaderboard:points)维护积分排名。
 * 启动时从 MySQL 全量重建,保证 Redis 丢失后也能自愈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    public static final String KEY_LEADERBOARD = "leaderboard:points";

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        List<User> users = userMapper.findAllForLeaderboard();
        redis.delete(KEY_LEADERBOARD);
        for (User user : users) {
            redis.opsForZSet().add(KEY_LEADERBOARD, String.valueOf(user.getId()), user.getPoints());
        }
        log.info("排行榜已从 MySQL 重建,共 {} 人", users.size());
    }

    /** 结算后更新某用户的积分(ZADD 绝对值,天然幂等) */
    public void updatePoints(Long userId, int points) {
        redis.opsForZSet().add(KEY_LEADERBOARD, String.valueOf(userId), points);
    }

    public List<LeaderboardVO> topN(int limit, Long currentUserId) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(KEY_LEADERBOARD, 0, limit - 1);
        List<LeaderboardVO> result = new ArrayList<>();
        if (tuples == null || tuples.isEmpty()) {
            return result;
        }
        List<Long> ids = tuples.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();
        Map<Long, User> users = userMapper.findByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long userId = Long.valueOf(tuple.getValue());
            User user = users.get(userId);
            if (user == null) {
                continue;
            }
            LeaderboardVO vo = new LeaderboardVO();
            vo.setRank(rank++);
            vo.setUserId(userId);
            vo.setNickname(user.getNickname());
            vo.setPoints(user.getPoints());
            vo.setIsMe(userId.equals(currentUserId));
            result.add(vo);
        }
        return result;
    }
}
