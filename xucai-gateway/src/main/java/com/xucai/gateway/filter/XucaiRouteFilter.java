package com.xucai.gateway.filter;

import com.xucai.authsdk.gateway.util.AuthUtil;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.common.domain.R;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Explicit customer and merchant routes; unlisted routes are denied by default. */
@Component
@Profile("xucai")
public class XucaiRouteFilter implements GlobalFilter, Ordered {
    private final AuthUtil auth;
    private final AntPathMatcher paths = new AntPathMatcher();
    public XucaiRouteFilter(AuthUtil auth) { this.auth = auth; }
    private boolean matches(String path, String... patterns) {
        for (String pattern : patterns) if (paths.match(pattern, path)) return true;
        return false;
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        // Procurement path identifiers are plain digits/UUIDs. Queries may still contain Unicode.
        // Reject alternate path spellings before Netty/Tomcat can normalize them differently.
        if (rawPath.contains("%") || rawPath.contains(";") || rawPath.contains("\\") || rawPath.contains("//")
                || java.util.Arrays.stream(rawPath.split("/")).anyMatch(s -> s.equals(".") || s.equals("..")))
            return reject(exchange, HttpStatus.NOT_FOUND);
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        boolean anonymous = ("POST".equals(method) && matches(path, "/auth/accounts/login", "/auth/accounts/admin/login"))
                || ("GET".equals(method) && matches(path, "/auth/accounts/refresh", "/auth/jwks", "/catalog/products", "/catalog/products/**", "/trade/sale-items/batch"));
        if (anonymous) return chain.filter(exchange);
        boolean merchant = matches(path, "/catalog/admin/products", "/catalog/admin/products/**", "/catalog/admin/doc-chunks", "/catalog/admin/doc-chunks/**",
                "/trade/admin/sale-items/**", "/trade/admin/requirements", "/trade/admin/requirements/**", "/trade/admin/plans/**",
                "/trade/admin/purchase-orders", "/trade/admin/purchase-orders/**", "/ai/admin/requirements/**", "/ai/admin/retrieval/**");
        boolean buyer = matches(path, "/trade/cart", "/trade/checkout-intents", "/trade/checkout-intents/**", "/trade/requirements", "/trade/requirements/**", "/trade/plans/**", "/trade/purchase-orders", "/trade/purchase-orders/**", "/ai/runs", "/ai/runs/**", "/ai/assistant/sessions", "/ai/assistant/sessions/**");
        boolean shared = ("GET".equals(method) && path.equals("/users/users/me"))
                || ("POST".equals(method) && path.equals("/auth/accounts/logout"));
        if (!merchant && !buyer && !shared) return reject(exchange, HttpStatus.NOT_FOUND);
        String token = exchange.getRequest().getHeaders().getFirst("authorization");
        R<LoginUserDTO> user = auth.parseToken(token == null ? "" : token);
        if (!user.success() || user.getData() == null || user.getData().getUserId() == null) return reject(exchange, HttpStatus.UNAUTHORIZED);
        Long role = user.getData().getRoleId();
        if ((merchant && !Long.valueOf(1).equals(role)) || (buyer && !Long.valueOf(2).equals(role))
                || (shared && !Long.valueOf(1).equals(role) && !Long.valueOf(2).equals(role))) return reject(exchange, HttpStatus.FORBIDDEN);
        return chain.filter(exchange);
    }
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":" + status.value() + ",\"msg\":\"Request denied\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
    // Must see the service prefix before StripPrefix (order 1).
    @Override public int getOrder() { return -200; }
}
