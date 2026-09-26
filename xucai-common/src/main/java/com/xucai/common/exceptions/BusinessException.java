package com.xucai.common.exceptions;

/** 新采购接口中需要明确区分的冲突、未找到及依赖不可用。 */
public class BusinessException extends CommonException {
    private final int status;
    public BusinessException(int status, String message) {
        super(status, message);
        this.status = status;
    }
    @Override public int getStatus() { return status; }
}
