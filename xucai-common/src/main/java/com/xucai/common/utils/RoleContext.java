package com.xucai.common.utils;

/** 与 UserContext 一起使用，只保存网关传递的角色，并在请求结束清理。 */
public final class RoleContext {
    private static final ThreadLocal<Long> ROLE = new ThreadLocal<>();
    private RoleContext() { }
    public static Long get() { return ROLE.get(); }
    public static void set(Long role) { ROLE.set(role); }
    public static void clear() { ROLE.remove(); }
}
