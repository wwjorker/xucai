package com.xucai.auth.controller;

import com.xucai.api.dto.auth.RoleDTO;
import com.xucai.auth.domain.po.Role;
import com.xucai.auth.service.IRoleService;
import com.xucai.common.exceptions.ForbiddenException;
import com.xucai.common.utils.MallIdentity;
import com.xucai.common.utils.RoleContext;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal xucai-user profile enrichment; the public gateway does not expose /roles. */
@RestController
@Profile("xucai")
@RequestMapping("/roles")
@RequiredArgsConstructor
public class XucaiRoleController {
    private final IRoleService roleService;

    @Operation(hidden = true)
    @GetMapping("/{id}")
    public RoleDTO queryRoleById(@PathVariable("id") Long id) {
        MallIdentity.merchant();
        if (!RoleContext.get().equals(id)) {
            throw new ForbiddenException("只能查询当前账户的角色");
        }
        Role role = roleService.getById(id);
        return role == null ? null : role.toDTO();
    }
}
