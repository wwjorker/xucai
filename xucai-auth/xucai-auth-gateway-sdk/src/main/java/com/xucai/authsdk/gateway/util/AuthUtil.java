package com.xucai.authsdk.gateway.util;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.json.JSONObject;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import com.xucai.common.domain.R;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.common.utils.StringUtils;
import static com.xucai.auth.common.constants.AuthErrorInfo.Code.*;
import static com.xucai.auth.common.constants.AuthErrorInfo.Msg.*;
import static com.xucai.auth.common.constants.JwtConstants.*;

/** Validates identity; route authorization belongs to the gateway route filter. */
public class AuthUtil {
    private final JwtSignerHolder jwtSignerHolder;
    public AuthUtil(JwtSignerHolder jwtSignerHolder) {
        this.jwtSignerHolder = jwtSignerHolder;
    }

    public R<LoginUserDTO> parseToken(String token) {
        // 1.校验token是否为空
        if(StringUtils.isBlank(token)){
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        JWT jwt = null;
        try {
            jwt = JWT.of(token).setSigner(jwtSignerHolder.getJwtSigner());
        } catch (Exception e) {
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        // 2.校验jwt是否有效
        if (!jwt.verify()) {
            // 验证失败，返回空
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        // 3.校验是否过期
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            return R.error(EXPIRED_TOKEN_CODE, EXPIRED_TOKEN);
        }
        // 4.数据格式校验
        Object userPayload = jwt.getPayload(PAYLOAD_USER_KEY);
        if (userPayload == null) {
            // 数据为空
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN_PAYLOAD);
        }

        // 5.数据解析
        LoginUserDTO userDTO;
        try {
            userDTO = ((JSONObject)userPayload).toBean(LoginUserDTO.class);
        } catch (RuntimeException e) {
            // token格式有误
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN_PAYLOAD);
        }

        // 6.返回
        return R.ok(userDTO);
    }

}
