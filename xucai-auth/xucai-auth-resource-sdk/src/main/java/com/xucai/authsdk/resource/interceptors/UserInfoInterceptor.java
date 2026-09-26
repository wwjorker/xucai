package com.xucai.authsdk.resource.interceptors;

import com.xucai.auth.common.constants.JwtConstants;
import com.xucai.common.utils.TokenContext;
import com.xucai.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
        UserContext.removeUser();
        TokenContext.removeToken();
        com.xucai.common.utils.RoleContext.clear();
        // 1.尝试获取头信息中的用户信息
        String authorization = request.getHeader(JwtConstants.USER_HEADER);
        // 2.判断是否为空
        if (authorization == null) {
            return true;
        }
        // 3.转为用户id并保存
        try {
            Long userId = Long.valueOf(authorization);
            UserContext.setUser(userId);
            TokenContext.setToken(request.getHeader(JwtConstants.TOKEN_HEADER));
            String role = request.getHeader("role-info");
            if (role != null) com.xucai.common.utils.RoleContext.set(Long.valueOf(role));
            return true;
        } catch (NumberFormatException e) {
            log.error("用户身份信息格式不正确，{}, 原因：{}", authorization, e.getMessage());
            return true;
        }
    }

    @Override
    public void afterCompletion(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理用户信息
        UserContext.removeUser();
        TokenContext.removeToken();
        com.xucai.common.utils.RoleContext.clear();
    }

}
