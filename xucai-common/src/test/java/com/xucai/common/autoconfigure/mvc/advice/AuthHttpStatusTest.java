package com.xucai.common.autoconfigure.mvc.advice;

import com.xucai.common.constants.Constant;
import com.xucai.common.domain.R;
import com.xucai.common.exceptions.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

class AuthHttpStatusTest {
    @Test
    void retrievalErrorsPreserveConflictDisabledAndNotFoundStatus() {
        for (String path : new String[]{"/admin/retrieval/build", "/admin/retrieval/search", "/admin/retrieval/operations/id", "/assistant/sessions", "/assistant/sessions/missing", "/cart", "/checkout-intents", "/checkout-intents/missing"}) {
            for (int status : new int[]{400, 409, 503, 404}) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
                request.addHeader(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME);
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
                try {
                    ResponseEntity<?> response = (ResponseEntity<?>) new CommonExceptionAdvice()
                            .handleBadRequestException(new com.xucai.common.exceptions.BusinessException(status, "检索拒绝"));
                    assertEquals(status, response.getStatusCode().value());
                    assertEquals(status, ((R<?>) response.getBody()).getCode());
                } finally { RequestContextHolder.resetRequestAttributes(); }
            }
        }
    }

    @Test
    void gatewayAuthenticationFailurePreservesHttpStatusAndBusinessEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        try {
            ResponseEntity<?> response = (ResponseEntity<?>) new CommonExceptionAdvice()
                    .handleBadRequestException(new UnauthorizedException("请先登录"));
            assertEquals(401, response.getStatusCode().value());
            assertEquals(401, ((R<?>) response.getBody()).getCode());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
