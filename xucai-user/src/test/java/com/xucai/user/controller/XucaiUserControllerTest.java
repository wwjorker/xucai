package com.xucai.user.controller;

import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.user.domain.vo.UserDetailVO;
import com.xucai.user.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XucaiUserControllerTest {
    private AnnotationConfigApplicationContext context(IUserService users) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("xucai");
        context.registerBean(IUserService.class, () -> users);
        context.register(XucaiUserController.class);
        context.refresh();
        return context;
    }

    @Test void procurementProfileRegistersOnlyTheNarrowUserController() {
        IUserService users = mock(IUserService.class);
        try (var context = context(users)) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xucai.user.controller.UserController"));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xucai.user.controller.StaffController"));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xucai.user.controller.UnavailableMerchantController"));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xucai.user.controller.UnavailableCustomerController"));
            assertEquals(1, context.getBeansWithAnnotation(RestController.class).size());
            assertNotNull(context.getBean(XucaiUserController.class));
            verifyNoInteractions(users);
        }
    }

    @Test void actualHandlerMappingContainsOnlyIdentityAndInternalLoginRoutes() {
        try (var context = context(mock(IUserService.class))) {
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            Set<String> routes = mapping.getHandlerMethods().keySet().stream()
                    .flatMap(info -> info.getPatternValues().stream().flatMap(path ->
                            info.getMethodsCondition().getMethods().stream().map(method -> method.name() + " " + path)))
                    .collect(Collectors.toSet());
            assertEquals(2, mapping.getHandlerMethods().size());
            assertEquals(Set.of("GET /users/me", "POST /users/detail/{isStaff}"), routes);
        }
    }

    @Test void bothRemainingOperationsDelegateToTheExistingUserService() {
        IUserService users = mock(IUserService.class);
        UserDetailVO me = new UserDetailVO();
        LoginFormDTO login = new LoginFormDTO();
        LoginUserDTO customer = new LoginUserDTO();
        LoginUserDTO staff = new LoginUserDTO();
        when(users.myInfo()).thenReturn(me);
        when(users.queryUserDetail(login, false)).thenReturn(customer);
        when(users.queryUserDetail(login, true)).thenReturn(staff);
        try (var context = context(users)) {
            XucaiUserController controller = context.getBean(XucaiUserController.class);
            assertSame(me, controller.me());
            assertSame(customer, controller.queryUserDetail(login, false));
            assertSame(staff, controller.queryUserDetail(login, true));
            verify(users).myInfo();
            verify(users).queryUserDetail(login, false);
            verify(users).queryUserDetail(login, true);
            verifyNoMoreInteractions(users);
        }
    }
    @Test void unsupportedLoginNeverAttemptsPasswordOrSms() {
        var service=spy(new com.xucai.user.service.impl.UserServiceImpl());
        for(Integer type:new Integer[]{null,0,2,3}) {
            var input=new LoginFormDTO();input.setType(type);
            assertThrows(com.xucai.common.exceptions.BadRequestException.class,()->service.queryUserDetail(input,false));
        }
        verify(service,never()).loginByPw(any());
    }
    @Test void passwordCustomerKeepsPersistedRoleAndCannotUseMerchantLogin() {
        var service=spy(new com.xucai.user.service.impl.UserServiceImpl());
        var input=new LoginFormDTO();input.setType(1);
        var user=new com.xucai.user.domain.po.User();user.setId(10003L);user.setType(com.xucai.common.enums.UserType.CUSTOMER);
        doReturn(user).when(service).loginByPw(input);
        var result=service.queryUserDetail(input,false);
        assertEquals(10003L,result.getUserId());assertEquals(2L,result.getRoleId());
        assertThrows(com.xucai.common.exceptions.BadRequestException.class,()->service.queryUserDetail(input,true));
    }
}
