package com.linkduel.controller;

import com.linkduel.common.Result;
import com.linkduel.dto.UserVO;
import com.linkduel.entity.User;
import com.linkduel.security.AuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    public Result<UserVO> me(@RequestAttribute(AuthInterceptor.ATTR_CURRENT_USER) User currentUser) {
        return Result.ok(UserVO.from(currentUser));
    }
}
