package com.xucai.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xucai.user.domain.po.UserDetail;
import com.xucai.user.domain.query.UserPageQuery;
import com.xucai.common.enums.UserType;

import java.util.List;

/**
 * <p>
 * 用户详情表 服务类
 * </p>
 *
 * @since 2022-08-15
 */
public interface IUserDetailService extends IService<UserDetail> {

    UserDetail queryById(Long userId);

    List<UserDetail> queryByIds(List<Long> ids);

    Page<UserDetail> queryUserDetailByPage(UserPageQuery pageQuery, UserType type);
}
