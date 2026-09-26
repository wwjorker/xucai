package com.xucai.gateway.filter;

import com.xucai.authsdk.gateway.util.AuthUtil;
import com.xucai.common.domain.R;
import com.xucai.common.domain.dto.LoginUserDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XucaiRouteFilterTest {
    private final AuthUtil auth = mock(AuthUtil.class);
    private final XucaiRouteFilter filter = new XucaiRouteFilter(auth);
    private void expect(String method, String path, String token, int status) {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method),java.net.URI.create(path))
                .header("authorization",token).header("user-info","10002").header("role-info","1"));
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(ex, e -> {forwarded.set(true);return Mono.empty();}).block();
        assertEquals(status==200,forwarded.get(),method+" "+path);
        if(status!=200) assertEquals(status,ex.getResponse().getStatusCode().value());
    }
    @Test void unknownLegacyRoutesNeverReachControllersEvenWithMerchantToken() {
        for(String path:new String[]{"/users/users","/users/users/10002/password/default","/users/users/detail/true",
                "/auth/privileges","/auth/roles","/auth/menus","/catalog/unsupported-category/add","/catalog/unsupported-items/save",
                "/users/unsupported/register","/trade/carts","/ai/session","/catalog/v3/api-docs"}) {
            expect("POST",path,"",404); expect("GET",path,"merchant",404);
        }
        verifyNoInteractions(auth);
    }
    @Test void anonymousAllowedOnlyForPublicReadAndLogin() {
        expect("GET","/catalog/products/1/docs","",200);
        expect("POST","/auth/accounts/admin/login","",200);
        expect("GET","/auth/accounts/refresh","",200);
        expect("POST","/catalog/products/1/docs","",404);
        when(auth.parseToken("")).thenReturn(R.error(401,"invalid"));
        expect("GET","/users/users/me","",401);
        expect("POST","/trade/requirements","",401);
        expect("POST","/catalog/admin/products","",401);
    }
    @Test void buyerAndMerchantRemainSeparateAndSharedMeWorks() {
        LoginUserDTO buyer=new LoginUserDTO();buyer.setUserId(10001L);buyer.setRoleId(2L);
        LoginUserDTO merchant=new LoginUserDTO();merchant.setUserId(10002L);merchant.setRoleId(1L);
        when(auth.parseToken("buyer")).thenReturn(R.ok(buyer));
        when(auth.parseToken("merchant")).thenReturn(R.ok(merchant));
        expect("GET","/users/users/me","buyer",200);
        expect("GET","/users/users/me","merchant",200);
        expect("POST","/catalog/admin/products","buyer",403);
        expect("GET","/ai/runs","merchant",403);
        expect("GET","/trade/requirements/1","buyer",200);
        expect("GET","/ai/admin/retrieval/operations/1","merchant",200);
        for(String path:new String[]{"/ai/assistant/sessions","/ai/assistant/sessions/abc/turns","/trade/cart","/trade/checkout-intents","/trade/checkout-intents/abc/confirm"}) {
            expect("POST",path,"buyer",200);
            expect("POST",path,"merchant",403);
        }
    }
    @Test void executesBeforeStripPrefix() { assertTrue(filter.getOrder()<1); }
    @Test void alternatePathsCannotTurnPublicReadsIntoAdminRoutes() {
        for(String path:new String[]{"/catalog/products/../admin/products", "/catalog/products/%2e%2e/admin/products",
                "/catalog/products/%252e%252e/admin/products", "/catalog/products;x/1", "/catalog//products/1",
                "/catalog/products/%2fadmin/products"}) expect("GET",path,"",404);
        verifyNoInteractions(auth);
    }
}
