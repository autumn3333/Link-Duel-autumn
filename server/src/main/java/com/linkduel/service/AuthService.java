package com.linkduel.service;

import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.dto.LoginResponse;
import com.linkduel.dto.UserVO;
import com.linkduel.entity.User;
import com.linkduel.mapper.UserMapper;
import com.linkduel.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LoginResponse login(String email, String password) {
        User user = userMapper.findByEmail(email);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 统一报"邮箱或密码错误",不泄露账号是否存在
            throw new BizException(ErrorCode.WRONG_PASSWORD);
        }
        String token = jwtUtil.generateToken(user.getId());
        return new LoginResponse(token, UserVO.from(user));
    }
}
