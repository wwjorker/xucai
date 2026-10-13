package com.xucai.api.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "登录表单实体")
public class LoginFormDTO {
    @Schema(description = "登录方式：1-密码登录", example = "1", required = true)
    @NotNull
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(1)
    private Integer type;
    @Schema(description = "用户名（虚构演示账号示例）", example = "buyer_demo")
    private String username;
    @Schema(description = "手机号（示例为不可拨打的占位值）", example = "00000000000")
    private String cellPhone;
    @Schema(description = "密码（演示账号密码见安装文档）", example = "<演示账号密码>", required = true)
    @NotNull
    private String password;
    @Schema(description = "7天免密登录", example = "true")
    private Boolean rememberMe = false;
}
