package com.linkduel.seed;

import com.linkduel.entity.User;
import com.linkduel.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时保证两个演示账号存在(幂等:按 email 逐个检查)。
 * 评审无需手工操作数据库即可直接登录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final String DEMO_PASSWORD = "Test123456!";

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedUser("player_a@example.com", "玩家A");
        seedUser("player_b@example.com", "玩家B");
        log.info("种子账号检查完成");
    }

    private void seedUser(String email, String nickname) {
        if (userMapper.findByEmail(email) != null) {
            return;
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        user.setNickname(nickname);
        user.setPoints(0);
        user.setWins(0);
        user.setLosses(0);
        user.setDraws(0);
        userMapper.insert(user);
        log.info("已创建种子账号: {} (id={})", email, user.getId());
    }
}
