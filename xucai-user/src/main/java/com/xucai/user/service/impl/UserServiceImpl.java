package com.xucai.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xucai.api.client.auth.AuthClient;
import com.xucai.api.dto.auth.RoleDTO;
import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.common.enums.UserType;
import com.xucai.common.exceptions.BadRequestException;
import com.xucai.common.exceptions.ForbiddenException;
import com.xucai.common.exceptions.UnauthorizedException;
import com.xucai.common.utils.AssertUtils;
import com.xucai.common.utils.UserContext;
import com.xucai.user.domain.po.User;
import com.xucai.user.domain.po.UserDetail;
import com.xucai.user.domain.vo.UserDetailVO;
import com.xucai.user.enums.UserStatus;
import com.xucai.user.mapper.UserMapper;
import com.xucai.user.service.IUserDetailService;
import com.xucai.user.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import static com.xucai.user.constants.UserConstants.*;
import static com.xucai.user.constants.UserErrorInfo.Msg.*;


/**
 * <p>
 * 用户账户表 服务实现类
 * </p>
 *
 * @since 2022-06-28
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuthClient authClient;
    @Autowired
    private IUserDetailService detailService;

    @Override
    public LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff) {
        if (!Integer.valueOf(1).equals(loginDTO.getType())) {
            throw new BadRequestException("仅支持密码登录");
        }
        User user = loginByPw(loginDTO);
        // 5.判断用户类型与登录方式是否匹配
        if (isStaff ^ user.getType() != UserType.CUSTOMER) {
            throw new BadRequestException(isStaff ? "非管理端用户" : "非客户端用户");
        }
        // 6.封装返回
        LoginUserDTO userDTO = new LoginUserDTO();
        userDTO.setUserId(user.getId());
        userDTO.setRoleId(handleRoleId(user));
        return userDTO;
    }



    @Override
    public UserDetailVO myInfo() {
        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        // 2.查询用户
        UserDetail userDetail = detailService.queryById(userId);
        AssertUtils.isNotNull(userDetail, USER_ID_NOT_EXISTS);
        // 3.封装vo
        UserType type = userDetail.getType();
        // 3.1.基本信息
        UserDetailVO vo = BeanUtil.toBean(userDetail, UserDetailVO.class);
        // 3.2.详情信息
        switch (type) {
            case STAFF:
                RoleDTO roleDTO = authClient.queryRoleById(userDetail.getRoleId());
                vo.setRoleName(roleDTO == null ? "" : roleDTO.getName());
                break;
            case CUSTOMER:
                vo.setRoleName(CUSTOMER_ROLE_NAME);
                break;
            case MERCHANT:
                vo.setRoleName(MERCHANT_ROLE_NAME);
                break;
            default:
                break;
        }
        return vo;
    }













    public User loginByPw(LoginFormDTO loginDTO) {
        // 1.数据校验
        String username = loginDTO.getUsername();
        String cellPhone = loginDTO.getCellPhone();
        if (StrUtil.isBlank(username) && StrUtil.isBlank(cellPhone)) {
            throw new BadRequestException(INVALID_UN);
        }
        // 2.根据用户名或手机号查询
        User user = lambdaQuery()
                .eq(StrUtil.isNotBlank(username), User::getUsername, username)
                .eq(StrUtil.isNotBlank(cellPhone), User::getCellPhone, cellPhone)
                .one();
        AssertUtils.isNotNull(user, INVALID_UN_OR_PW);
        // 3.校验是否禁用
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException(USER_FROZEN);
        }
        // 4.校验密码
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BadRequestException(INVALID_UN_OR_PW);
        }

        return user;
    }

    private Long handleRoleId(User user) {
        Long roleId = 0L;
        switch (user.getType()) {
            case CUSTOMER:
                roleId = CUSTOMER_ROLE_ID;
                break;
            case MERCHANT:
                roleId = MERCHANT_ROLE_ID;
                break;
            case STAFF:
                UserDetail detail = detailService.getById(user.getId());
                roleId = detail.getRoleId();
                break;
        }
        return roleId;
    }

}
