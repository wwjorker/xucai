package com.xucai.user.service;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.user.domain.po.User;
import com.xucai.user.domain.vo.UserDetailVO;
public interface IUserService extends IService<User> {
    LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff);
    UserDetailVO myInfo();
}
