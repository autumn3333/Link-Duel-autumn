package com.linkduel.mapper;

import com.linkduel.entity.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMapper {

    User findByEmail(@Param("email") String email);

    User findById(@Param("id") Long id);

    List<User> findByIds(@Param("ids") List<Long> ids);

    int insert(User user);

    /**
     * 结算时按绝对值更新统计(与 SELECT ... FOR UPDATE 配合使用)
     */
    int updateStats(User user);

    /** 启动时重建排行榜用 */
    List<User> findAllForLeaderboard();
}
