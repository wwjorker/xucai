package com.xucai.auth.controller;


import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.auth.common.constants.JwtConstants;
import com.xucai.auth.service.IAccountService;
import com.xucai.common.exceptions.BadRequestException;
import com.xucai.common.utils.WebUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 账户登录相关接口
 */
@RestController
@RequestMapping("/accounts")
@Tag(name = "账户管理")
@RequiredArgsConstructor
public class AccountController {

    private final IAccountService accountService;

    @Operation(summary  = "登录并获取token")
    @PostMapping(value = "/login")
    public String loginByPw(@RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, false);
    }

    @Operation(summary  = "管理端登录并获取token")
    @PostMapping(value = "/admin/login")
    public String adminLoginByPw(@RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, true);
    }

    @Operation(summary  ="退出登录")
    @PostMapping(value = "/logout")
    public void logout() {
        accountService.logout();
    }

    @Operation(summary = "刷新token")
    @GetMapping(value = "/refresh")
    public String refreshToken(
            @CookieValue(value = JwtConstants.REFRESH_HEADER, required = false) String customerToken,
            @CookieValue(value = JwtConstants.ADMIN_REFRESH_HEADER, required = false) String adminToken,
            @RequestParam(defaultValue = "buyer") String client
    ) {
        if (!"buyer".equals(client) && !"merchant".equals(client)) throw new BadRequestException("未知客户端类型");
        boolean merchant = "merchant".equals(client);
        String token = merchant ? adminToken : customerToken;
        if (token == null) {
            throw new com.xucai.common.exceptions.UnauthorizedException("登录已过期，请重新登录");
        }
        return accountService.refreshToken(WebUtils.cookieBuilder().decode(token), merchant);
    }
}
