package com.linkduel.service;

import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.GameRecord;
import com.linkduel.entity.User;
import com.linkduel.mapper.GameRecordMapper;
import com.linkduel.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 结算落库:单事务 = 提交点。
 * 独立 Service 是为了让 @Transactional 生效(同类内自调用不会经过代理)。
 * INSERT 的 uk_game_id 唯一键冲突会抛 DuplicateKeyException,由调用方走修复路径。
 */
@Service
@RequiredArgsConstructor
public class SettlementPersistService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final GameRecordMapper gameRecordMapper;
    private final UserMapper userMapper;

    public record PersistOutcome(int newPointsA, int newPointsB) {
    }

    @Transactional
    public PersistOutcome persist(RoomState room, SettlementService.Outcome outcome) {
        long now = System.currentTimeMillis();

        GameRecord record = new GameRecord();
        record.setGameId(room.getRoomId());
        record.setPlayerAId(room.getPlayerAId());
        record.setPlayerBId(room.getPlayerBId());
        record.setScoreA(room.getScoreA());
        record.setScoreB(room.getScoreB());
        record.setWinnerId(outcome.getWinnerId());
        record.setStatus(outcome.getStatus());
        record.setReason(outcome.getReason());
        record.setStartedAt(toLocal(room.getStartedAt() > 0 ? room.getStartedAt() : room.getCreatedAt()));
        record.setEndedAt(toLocal(now));
        // 先 INSERT:uk_game_id 唯一键是第一道幂等防线(重复结算在此抛异常回滚)
        gameRecordMapper.insert(record);

        // 行锁读取双方统计,事务内按绝对值更新
        User userA = userMapper.findByIdForUpdate(room.getPlayerAId());
        User userB = userMapper.findByIdForUpdate(room.getPlayerBId());
        if (userA == null || userB == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "结算时用户不存在");
        }

        userA.setPoints(userA.getPoints() + outcome.getDeltaA());
        userB.setPoints(userB.getPoints() + outcome.getDeltaB());
        applyWinLossDraw(userA, userB, outcome);
        userMapper.updateStats(userA);
        userMapper.updateStats(userB);

        return new PersistOutcome(userA.getPoints(), userB.getPoints());
    }

    private void applyWinLossDraw(User userA, User userB, SettlementService.Outcome outcome) {
        if (outcome.getWinnerId() == null) {
            if ("finished".equals(outcome.getStatus())) { // 平局
                userA.setDraws(userA.getDraws() + 1);
                userB.setDraws(userB.getDraws() + 1);
            }
            return; // cancelled:不计胜负
        }
        if (outcome.getWinnerId().equals(userA.getId())) {
            userA.setWins(userA.getWins() + 1);
            userB.setLosses(userB.getLosses() + 1);
        } else {
            userB.setWins(userB.getWins() + 1);
            userA.setLosses(userA.getLosses() + 1);
        }
    }

    private static LocalDateTime toLocal(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
    }
}
