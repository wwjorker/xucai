package com.xucai.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户详情")
public class UserDetailVO {
    @Schema(description = "用户id（虚构演示账号示例）", example = "10001")
    private Long id;

    @Schema(description = "名字（虚构演示账号示例）", example = "演示客户")
    private String name;

    @Schema(description = "头像地址（演示账号默认不设置）")
    private String icon;

    @Schema(description = "手机号（示例为不可拨打的占位值）", example = "00000000000")
    private String cellPhone;

    @Schema(description = "用户名（虚构演示账号示例）", example = "buyer_demo")
    private String username;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "区")
    private String district;

    @Schema(description = "性别：0-男性，1-女性", example = "0")
    private Integer gender;

    @Schema(description = "注册时间（虚构时间示例）", example = "2026-09-26T00:00:00")
    private LocalDateTime createTime;

    @Schema(description = "角色名称", example = "客户")
    private String roleName;
}
