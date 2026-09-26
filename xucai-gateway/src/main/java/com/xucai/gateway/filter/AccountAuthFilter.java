package com.xucai.gateway.filter;

import com.xucai.authsdk.gateway.util.AuthUtil;
import com.xucai.common.domain.R;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.gateway.config.AuthProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.xucai.auth.common.constants.JwtConstants.*;

@Component
public class AccountAuthFilter implements GlobalFilter, Ordered {

    private final AuthUtil authUtil;
    private final AuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AccountAuthFilter(AuthUtil authUtil, AuthProperties authProperties, XucaiRouteFilter routeFilter) {
        // Refuse startup if the matching route authorization filter is not active.
        java.util.Objects.requireNonNull(routeFilter, "Route authorization is required");
        this.authUtil = authUtil;
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.只信任网关验证的身份；白名单和未登记权限的路径也必须先清理。
        exchange = exchange.mutate().request(builder -> builder.headers(headers -> {
            headers.remove(USER_HEADER);
            headers.remove(TOKEN_HEADER);
            headers.remove("role-info");
        })).build();
        // 获取清理后的请求信息
        ServerHttpRequest request = exchange.getRequest();
        // String method = request.getMethodValue();
        String method = request.getMethod().name();
        String path = request.getPath().toString();
        String antPath = method + ":" + path;

        // 2.判断是否是无需登录的路径
        // StripPrefix通常先执行，此时路径为/admin/**；兼容过滤前的目录/交易路径。
        // /accounts/admin/login是登录入口，不能误当成已登录商家接口。
        boolean merchantPath = antPathMatcher.match("/admin/**", path)
                || antPathMatcher.match("/catalog/admin/**", path) || antPathMatcher.match("/trade/admin/**", path);
        if(!merchantPath && isExcludePath(antPath)){
            // 直接放行
            return chain.filter(exchange);
        }

        // 3.尝试获取用户信息
        List<String> authHeaders = exchange.getRequest().getHeaders().get(AUTHORIZATION_HEADER);
        String token = authHeaders == null ? "" : authHeaders.get(0);
        R<LoginUserDTO> r = authUtil.parseToken(token);
        if (merchantPath) {
            if (!r.success()) throw new com.xucai.common.exceptions.UnauthorizedException("请先登录");
            if (!Long.valueOf(1L).equals(r.getData().getRoleId())) {
                throw new com.xucai.common.exceptions.ForbiddenException("仅商家可以维护商品与售卖信息");
            }
        }

        // 4.如果用户是登录状态，尝试更新请求头，传递用户信息
        if (r.success()) {
            exchange = exchange.mutate()
                    .request(builder -> builder
                            .header(USER_HEADER, r.getData().getUserId().toString())
                            .header(TOKEN_HEADER, token)
                            .headers(headers -> {
                                if (r.getData().getRoleId() != null) headers.set("role-info", r.getData().getRoleId().toString());
                            })
                    )
                    .build();
        }

        // Route authorization has already run in XucaiRouteFilter.

        // 6.放行
        return chain.filter(exchange);
    }

    private boolean isExcludePath(String antPath) {
        for (String pathPattern : authProperties.getExcludePath()) {
            if(antPathMatcher.match(pathPattern, antPath)){
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
