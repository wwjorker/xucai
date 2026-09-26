package com.xucai.gateway.filter;

import com.xucai.authsdk.gateway.util.AuthUtil;
import com.xucai.common.domain.R;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.gateway.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.xucai.auth.common.constants.JwtConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountAuthFilterTest {
    @Test
    void gatewayIdentityFilterCannotStartWithoutRouteAuthorization() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(AuthUtil.class, () -> mock(AuthUtil.class))
                .withBean(AuthProperties.class, AuthProperties::new)
                .withUserConfiguration(AccountAuthFilter.class, XucaiRouteFilter.class);
        runner.run(context -> assertNotNull(context.getStartupFailure()));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("xucai"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(XucaiRouteFilter.class));
                    assertNotNull(context.getBean(AccountAuthFilter.class));
                });
    }

    private AuthUtil authUtil;
    private AccountAuthFilter filter;

    @BeforeEach
    void setUp() {
        authUtil = mock(AuthUtil.class);
        AuthProperties properties = new AuthProperties();
        properties.setExcludePath(Set.of("GET:/public/**"));
        filter = new AccountAuthFilter(authUtil, properties, new XucaiRouteFilter(authUtil));
    }

    @Test
    void anonymousUnregisteredPathDoesNotReceiveForgedIdentity() {
        when(authUtil.parseToken("")).thenReturn(R.error(401, "invalid token"));
        ServerWebExchange forwarded = forward(MockServerHttpRequest.get("/users/users/me")
                .header("User-Info", "999", "1000")
                .header("Token-Info", "forged-token").build());
        assertFalse(forwarded.getRequest().getHeaders().containsKey(USER_HEADER));
        assertFalse(forwarded.getRequest().getHeaders().containsKey(TOKEN_HEADER));
        verify(authUtil).parseToken("");
        verifyNoMoreInteractions(authUtil);
    }

    @Test
    void excludedPathAlsoStripsIdentityHeaders() {
        ServerWebExchange forwarded = forward(MockServerHttpRequest.get("/public/catalog")
                .header(USER_HEADER, "999").header(TOKEN_HEADER, "forged-token").build());
        assertFalse(forwarded.getRequest().getHeaders().containsKey(USER_HEADER));
        assertFalse(forwarded.getRequest().getHeaders().containsKey(TOKEN_HEADER));
        verifyNoInteractions(authUtil);
    }

    @Test
    void validTokenReplacesForgedIdentityAndExplicitlyPassesNewExchange() {
        LoginUserDTO user = new LoginUserDTO();
        user.setUserId(2L);
        user.setRoleId(2L);
        when(authUtil.parseToken("valid-token")).thenReturn(R.ok(user));
        MockServerWebExchange original = MockServerWebExchange.from(MockServerHttpRequest.get("/users/users/me")
                .header(AUTHORIZATION_HEADER, "valid-token")
                .header(USER_HEADER, "999", "1000").header(TOKEN_HEADER, "forged-token"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        filter.filter(original, next -> { captured.set(next); return Mono.empty(); }).block();
        assertNotSame(original, captured.get());
        assertEquals(java.util.List.of("2"), captured.get().getRequest().getHeaders().get(USER_HEADER));
        assertEquals(java.util.List.of("valid-token"), captured.get().getRequest().getHeaders().get(TOKEN_HEADER));
    }

    @Test
    void invalidTokenCannotCarryIdentityThroughUnregisteredPath() {
        when(authUtil.parseToken("invalid-token")).thenReturn(R.error(401, "invalid token"));
        ServerWebExchange forwarded = forward(MockServerHttpRequest.get("/users/users/me")
                .header(AUTHORIZATION_HEADER, "invalid-token")
                .header(USER_HEADER, "999").header(TOKEN_HEADER, "forged-token").build());
        assertNull(forwarded.getRequest().getHeaders().getFirst(USER_HEADER));
        assertNull(forwarded.getRequest().getHeaders().getFirst(TOKEN_HEADER));
    }

    private ServerWebExchange forward(MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        filter.filter(MockServerWebExchange.from(request), next -> {
            captured.set(next);
            return Mono.empty();
        }).block();
        assertNotNull(captured.get());
        return captured.get();
    }

    @Test
    void strippedMerchantPathIsProtectedButLoginEntryIsOpen() {
        when(authUtil.parseToken("")).thenReturn(R.error(401,"invalid"));
        assertThrows(com.xucai.common.exceptions.UnauthorizedException.class,
                () -> forward(MockServerHttpRequest.post("/admin/products").build()));
        assertDoesNotThrow(() -> forward(MockServerHttpRequest.post("/accounts/admin/login").build()));
    }

    @Test
    void merchantPathRejectsAnonymousAndBuyerEvenWithForgedRole() {
        when(authUtil.parseToken("")).thenReturn(R.error(401, "invalid"));
        assertThrows(com.xucai.common.exceptions.UnauthorizedException.class,
                () -> forward(MockServerHttpRequest.post("/catalog/admin/products").header("role-info", "1").build()));
        LoginUserDTO buyer = new LoginUserDTO(); buyer.setUserId(10001L); buyer.setRoleId(2L);
        when(authUtil.parseToken("buyer")).thenReturn(R.ok(buyer));
        assertThrows(com.xucai.common.exceptions.ForbiddenException.class,
                () -> forward(MockServerHttpRequest.put("/trade/admin/sale-items/1")
                        .header(AUTHORIZATION_HEADER,"buyer").header("role-info","1").build()));
    }

    @Test
    void merchantTokenSuppliesTrustedRole() {
        LoginUserDTO merchant = new LoginUserDTO(); merchant.setUserId(10002L); merchant.setRoleId(1L);
        when(authUtil.parseToken("merchant")).thenReturn(R.ok(merchant));
        ServerWebExchange forwarded = forward(MockServerHttpRequest.post("/catalog/admin/products")
                .header(AUTHORIZATION_HEADER,"merchant").header("role-info","2").build());
        assertEquals("1",forwarded.getRequest().getHeaders().getFirst("role-info"));
    }

    @Test
    void whitelistDoesNotBypassMerchantConventionAndPublicRequestLosesRole() {
        AuthProperties p = new AuthProperties(); p.setExcludePath(Set.of("POST:/**","GET:/**"));
        filter = new AccountAuthFilter(authUtil,p,new XucaiRouteFilter(authUtil));
        when(authUtil.parseToken("")).thenReturn(R.error(401,"invalid"));
        assertThrows(com.xucai.common.exceptions.UnauthorizedException.class,
                () -> forward(MockServerHttpRequest.post("/catalog/admin/products").build()));
        assertNull(forward(MockServerHttpRequest.get("/catalog/products").header("role-info","1").build())
                .getRequest().getHeaders().getFirst("role-info"));
    }
}
