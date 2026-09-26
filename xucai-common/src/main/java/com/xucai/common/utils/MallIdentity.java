package com.xucai.common.utils;

import com.xucai.common.exceptions.ForbiddenException;
import com.xucai.common.exceptions.UnauthorizedException;

public final class MallIdentity {
    private MallIdentity() { }
    public static long buyer() { return require(2L); }
    public static long merchant() { return require(1L); }
    private static long require(long role) {
        Long user=UserContext.getUser();
        if(user==null) throw new UnauthorizedException("请先登录");
        if(!Long.valueOf(role).equals(RoleContext.get())) throw new ForbiddenException("当前账户不能执行此操作");
        return user;
    }
}
