package com.xucai.common.autoconfigure.mvc.advice;

import com.xucai.common.constants.Constant;
import com.xucai.common.domain.R;
import com.xucai.common.exceptions.CommonException;
import com.xucai.common.exceptions.DbException;
import com.xucai.common.utils.WebUtils;
import feign.FeignException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CommonExceptionAdvice {

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Object handleUnreadableBody(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return processResponse(400, 400, "请求正文不是有效的JSON或字段类型错误");
    }

    @ExceptionHandler(DbException.class)
    public Object handleDbException(DbException e) {
        log.error("mysql数据库操作异常 -> ", e);
        return processResponse(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(CommonException.class)
    public Object handleBadRequestException(CommonException e) {
        log.error("自定义异常 -> {} , 状态码：{}, 异常原因：{}  ", e.getClass().getName(), e.getStatus(), e.getMessage());
        log.debug("", e);
        return processResponse(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(FeignException.class)
    public Object handleFeignException(FeignException e) {
        log.error("feign远程调用异常 -> ", e);
        return processResponse(e.status(), e.status(), e.contentUTF8());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors()
                .stream().map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("|"));
        log.error("请求参数校验异常 -> {}", msg);
        log.debug("", e);
        return processResponse(400, 400, msg);
    }

    @ExceptionHandler(BindException.class)
    public Object handleBindException(BindException e) {
        log.error("请求参数绑定异常 ->BindException， {}", e.getMessage());
        log.debug("", e);
        return processResponse(400, 400, "请求参数格式错误");
    }

    @ExceptionHandler(ServletException.class)
    public Object handleServletException(ServletException e) {
        log.error("参数异常 -> ServletException，{}", e.getMessage());
        log.debug("", e);
        return processResponse(400, 400, "请求参数异常");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Object handViolationException(ConstraintViolationException e) {
        log.error("请求参数异常 -> ConstraintViolationException, {}", e.getMessage());

        return processResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.value(),
                e.getConstraintViolations().stream().map(ConstraintViolation::getMessage).distinct().collect(Collectors.joining("|"))
        );
    }

    @ExceptionHandler(Exception.class)
    public Object handleRuntimeException(Exception e) {
        HttpServletRequest request = WebUtils.getRequest();
        if (null == request) {
            log.error("其他异常. ", e);
        } else {
            log.error("其他异常 uri : {} -> ", request.getRequestURI(), e);
        }
        return processResponse(500, 500, "服务器内部异常");
    }

    private Object processResponse(int status, int code, String msg) {
        // 1.标记响应异常已处理（避免重复处理）
        WebUtils.setResponseHeader(Constant.BODY_PROCESSED_MARK_HEADER, "true");
        // 2.网关保留原业务响应体；新采购接口同时使用真实HTTP失败状态。
        // 如果是微服务请求，http状态码基于异常原样返回，微服务自己做fallback处理
        return WebUtils.isGatewayRequest() ?
                ResponseEntity.status(status == 401 || status == 403 || isMallRequest() ? status : 200)
                        .body(R.error(code, msg).requestId(MDC.get(Constant.REQUEST_ID_HEADER)))
                : ResponseEntity.status(status).body(msg);
    }

    private boolean isMallRequest() {
        HttpServletRequest request = WebUtils.getRequest();
        if (request == null) return false;
        String path = request.getRequestURI();
        return path.equals("/assistant/sessions") || path.startsWith("/assistant/sessions/")
                || path.equals("/cart") || path.startsWith("/cart/")
                || path.equals("/checkout-intents") || path.startsWith("/checkout-intents/")
                || path.equals("/products") || path.startsWith("/products/")
                || path.equals("/admin/products") || path.startsWith("/admin/products/")
                || path.equals("/admin/doc-chunks") || path.startsWith("/admin/doc-chunks/")
                || path.equals("/admin/retrieval") || path.startsWith("/admin/retrieval/")
                || path.startsWith("/sale-items/") || path.startsWith("/admin/sale-items/")
                || path.equals("/requirements") || path.startsWith("/requirements/")
                || path.equals("/admin/requirements") || path.startsWith("/admin/requirements/")
                || path.equals("/admin/plans") || path.startsWith("/admin/plans/")
                || path.equals("/plans") || path.startsWith("/plans/")
                || path.equals("/purchase-orders") || path.startsWith("/purchase-orders/")
                || path.equals("/admin/purchase-orders") || path.startsWith("/admin/purchase-orders/")
                || path.equals("/runs") || path.startsWith("/runs/");
    }
}
