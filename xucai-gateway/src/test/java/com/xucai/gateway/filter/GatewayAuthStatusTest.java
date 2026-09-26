package com.xucai.gateway.filter;

import com.xucai.common.exceptions.UnauthorizedException;
import com.xucai.common.exceptions.ForbiddenException;
import com.xucai.gateway.exception.handler.GatewayExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import static org.junit.jupiter.api.Assertions.*;

class GatewayAuthStatusTest {
    @Test
    void authenticationAndAuthorizationFailuresKeepTheirHttpStatus() {
        for (var failure : new com.xucai.common.exceptions.CommonException[]{
                new UnauthorizedException("请先登录"), new ForbiddenException("无权访问")}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/users/users/me"));
            new GatewayExceptionHandler().handle(exchange, failure).block();
            assertEquals(failure.getStatus(), exchange.getResponse().getStatusCode().value());
            assertTrue(exchange.getResponse().getBodyAsString().block().contains("\"code\":" + failure.getCode()));
        }
    }
    @Test void missingRoutesAndUnavailableServicesKeepHttpFailureStatus() {
        for (int code : new int[]{404,503}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/unknown/path"));
            new GatewayExceptionHandler().handle(exchange,
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.valueOf(code))).block();
            assertEquals(code, exchange.getResponse().getStatusCode().value());
            assertTrue(exchange.getResponse().getBodyAsString().block().contains("\"code\":"+code));
        }
    }
}
