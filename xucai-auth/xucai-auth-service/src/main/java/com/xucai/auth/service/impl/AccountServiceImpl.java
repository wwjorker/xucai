package com.xucai.auth.service.impl;

import com.xucai.api.client.user.UserClient;
import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.auth.common.constants.JwtConstants;
import com.xucai.auth.service.IAccountService;
import com.xucai.auth.service.ILoginRecordService;
import com.xucai.auth.util.JwtTool;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.common.exceptions.BadRequestException;
import com.xucai.common.utils.BooleanUtils;
import com.xucai.common.utils.WebUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 账号表，平台内所有用户的账号、密码信息 服务实现类
 * </p>
 *
 * @since 2022-06-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements IAccountService{
    private final JwtTool jwtTool;
    private final UserClient userClient;
    private final ILoginRecordService loginRecordService;

    @Override
    public String login(LoginFormDTO loginDTO, boolean isStaff) {
        // 1.查询并校验用户信息
        LoginUserDTO detail = userClient.queryUserDetail(loginDTO, isStaff);
        if (detail == null) {
            throw new BadRequestException("登录信息有误");
        }

        // 2.基于JWT生成登录token
        // 2.1.设置记住我标记
        detail.setRememberMe(loginDTO.getRememberMe());
        // 2.2.生成token
        String token = generateToken(detail);

        // 3.计入登录信息表
        loginRecordService.loginSuccess(loginDTO.getCellPhone(), detail.getUserId());
        // 4.返回结果
        return token;
    }

    private String generateToken(LoginUserDTO detail) {
        // 2.2.生成access-token
        String token = jwtTool.createToken(detail);
        // 2.3.生成refresh-token，将refresh-token的JTI 保存到Redis
        String refreshToken = jwtTool.createRefreshToken(detail);
        // 2.4.将refresh-token写入用户cookie，并设置HttpOnly为true
        int maxAge = BooleanUtils.isTrue(detail.getRememberMe()) ?
                (int) JwtConstants.JWT_REMEMBER_ME_TTL.toSeconds() : -1;
        WebUtils.cookieBuilder()
                .name(detail.getRoleId() == 2 ? JwtConstants.REFRESH_HEADER : JwtConstants.ADMIN_REFRESH_HEADER)
                .value(refreshToken)
                .maxAge(maxAge)
                .httpOnly(true)
                .build();
        return token;
    }

    @Override
    public void logout() {
        // 删除jti
        jwtTool.cleanJtiCache();
        // 删除cookie
        WebUtils.cookieBuilder()
                .name(Long.valueOf(1L).equals(com.xucai.common.utils.RoleContext.get())
                        ? JwtConstants.ADMIN_REFRESH_HEADER : JwtConstants.REFRESH_HEADER)
                .value("")
                .maxAge(0)
                .httpOnly(true)
                .build();
    }

    @Override
    public String refreshToken(String refreshToken, boolean merchant) {
        // 1.校验refresh-token,校验JTI
        LoginUserDTO userDTO;
        try { userDTO = jwtTool.parseRefreshToken(refreshToken); }
        catch (BadRequestException e) { throw new com.xucai.common.exceptions.UnauthorizedException("登录已过期，请重新登录"); }
        if (!Long.valueOf(merchant ? 1L : 2L).equals(userDTO.getRoleId()))
            throw new com.xucai.common.exceptions.UnauthorizedException("登录身份与当前客户端不符");
        // 2.生成新的access-token、refresh-token
        return generateToken(userDTO);
    }
}
