package com.xucai.user.constants;


public interface UserConstants {
    String DEFAULT_PASSWORD = java.util.UUID.randomUUID().toString();

    Long CUSTOMER_ROLE_ID = 2L;
    String CUSTOMER_ROLE_NAME = "客户";

    Long MERCHANT_ROLE_ID = 3L;
    String MERCHANT_ROLE_NAME = "商家";

}
