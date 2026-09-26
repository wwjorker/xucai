package com.xucai.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.xucai.common.constants.ErrorInfo;
import com.xucai.common.exceptions.BadRequestException;
import lombok.Getter;

@Getter
public enum UserType implements BaseEnum{
    STAFF(1, "其他员工"),
    CUSTOMER(2, "客户"),
    MERCHANT(3, "商家"),
    ;
    @EnumValue
    int value;
    String desc;

    UserType(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public static UserType of(int value) {
        for (UserType type : UserType.values()) {
            if (type.getValue() == value) {
                return type;
            }
        }
        throw new BadRequestException(ErrorInfo.Msg.INVALID_USER_TYPE);
    }
}
