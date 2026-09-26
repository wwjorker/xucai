package com.xucai.authsdk.resource.interceptors;

import com.xucai.auth.common.constants.JwtConstants;
import com.xucai.common.utils.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

public class FeignRelayUserInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            return;
        }
        template.header(JwtConstants.USER_HEADER, userId.toString());
        Long role = com.xucai.common.utils.RoleContext.get();
        if (role != null) template.header("role-info", role.toString());
    }
}
