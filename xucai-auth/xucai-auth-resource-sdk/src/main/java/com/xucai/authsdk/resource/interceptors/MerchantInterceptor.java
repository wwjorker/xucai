package com.xucai.authsdk.resource.interceptors;

import com.xucai.common.exceptions.ForbiddenException;
import com.xucai.common.exceptions.UnauthorizedException;
import com.xucai.common.utils.RoleContext;
import com.xucai.common.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 服务端二次检查；应用端口属于可信内网，外部请求统一走网关。 */
public class MerchantInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserContext.getUser() == null) throw new UnauthorizedException("请先登录");
        if (!Long.valueOf(1L).equals(RoleContext.get())) throw new ForbiddenException("仅商家可以执行此操作");
        return true;
    }
}
