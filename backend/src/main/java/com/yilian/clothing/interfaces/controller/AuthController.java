package com.yilian.clothing.interfaces.controller;

import com.yilian.clothing.application.service.AuthAppService;
import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.interfaces.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录与会话（Q66/Q67）。 */
@Tag(name = "01 认证", description = "手机号+密码登录，返回 JWT")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthAppService authAppService;

    public AuthController(AuthAppService authAppService) {
        this.authAppService = authAppService;
    }

    @Operation(summary = "登录", description = "示例：{\"phone\":\"13800000000\",\"password\":\"123456\"}")
    @PostMapping("/login")
    public Result<AuthAppService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        if (request.phone() == null || request.phone().length() != 11) {
            throw BizException.of(ResultCode.PARAM_ERROR, "手机号必须是 11 位");
        }
        return Result.ok(authAppService.login(request.phone(), request.password()));
    }

    @Operation(summary = "当前登录人信息（含权限码，前端据此渲染菜单）")
    @GetMapping("/me")
    public Result<AuthAppService.UserView> me() {
        return Result.ok(authAppService.me());
    }

    public record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }
}
