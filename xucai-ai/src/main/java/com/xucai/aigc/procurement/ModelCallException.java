package com.xucai.aigc.procurement;

/** 固定的公开错误，不携带供应商原始响应、用户输入或凭据。 */
public class ModelCallException extends RuntimeException {
    private final String code;
    public ModelCallException(String code, String message) { super(message); this.code=code; }
    public String code() { return code; }
}
