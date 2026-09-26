package com.xucai.user.controller;

import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.user.domain.vo.UserDetailVO;
import com.xucai.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Procurement identity endpoints; account administration is not part of this application. */
@RestController
@Profile("xucai")
@RequestMapping("/users")
@RequiredArgsConstructor
public class XucaiUserController {
    private final IUserService userService;

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public UserDetailVO me() {
        return userService.myInfo();
    }

    /** Internal xucai-auth credential verification; never exposed by the procurement gateway. */
    @Operation(hidden = true)
    @PostMapping("/detail/{isStaff}")
    public LoginUserDTO queryUserDetail(
            @Valid @RequestBody LoginFormDTO loginDTO,
            @PathVariable("isStaff") boolean isStaff) {
        return userService.queryUserDetail(loginDTO, isStaff);
    }
}
