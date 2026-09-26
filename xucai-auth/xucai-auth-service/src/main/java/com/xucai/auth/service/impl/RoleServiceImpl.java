package com.xucai.auth.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xucai.auth.domain.po.Role;
import com.xucai.auth.mapper.RoleMapper;
import com.xucai.auth.service.IRoleService;
import org.springframework.stereotype.Service;
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements IRoleService {}
