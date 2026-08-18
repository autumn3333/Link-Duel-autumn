package com.linkduel.mapper;

import com.linkduel.entity.GameRecord;
import org.apache.ibatis.annotations.Param;

public interface GameRecordMapper {

    int insert(GameRecord record);

    GameRecord findByGameId(@Param("gameId") String gameId);
}
