package com.xucai.auth;

import com.xucai.auth.controller.XucaiRoleController;
import com.xucai.auth.domain.po.Role;
import com.xucai.auth.service.IRoleService;
import com.xucai.authsdk.resource.interceptors.FeignRelayUserInterceptor;
import com.xucai.authsdk.resource.interceptors.LoginAuthInterceptor;
import com.xucai.authsdk.resource.interceptors.UserInfoInterceptor;
import com.xucai.common.exceptions.ForbiddenException;
import com.xucai.common.exceptions.UnauthorizedException;
import com.xucai.common.utils.RoleContext;
import com.xucai.common.utils.UserContext;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class XucaiRoleControllerTest {
    @AfterEach void clear() { UserContext.removeUser(); RoleContext.clear(); }

    @Test void onlyMinimalRoleControllerRegistersInProcurementProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("portable", "xucai");
            context.registerBean(IRoleService.class, () -> mock(IRoleService.class));
            context.register(XucaiRoleController.class);
            context.refresh();
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xucai.auth.controller.RoleController"));
            assertEquals(1, context.getBeansOfType(XucaiRoleController.class).size());
        }
    }

    @Test void procurementControllerDoesNotRegisterForLegacyProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local");
            context.registerBean(IRoleService.class, () -> mock(IRoleService.class));
            context.register(XucaiRoleController.class);
            context.refresh();
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xucai.auth.controller.RoleController"));
            assertTrue(context.getBeansOfType(XucaiRoleController.class).isEmpty());
        }
    }

    @Test void rejectsAnonymousBuyerAndDifferentRoleBeforeReadingDatabase() {
        var service = mock(IRoleService.class);
        var controller = new XucaiRoleController(service);
        assertThrows(UnauthorizedException.class, () -> controller.queryRoleById(1L));
        UserContext.setUser(10001L); RoleContext.set(2L);
        assertThrows(ForbiddenException.class, () -> controller.queryRoleById(1L));
        RoleContext.set(1L);
        assertThrows(ForbiddenException.class, () -> controller.queryRoleById(2L));
        verifyNoInteractions(service);
    }

    @Test void mappingIsReadOnlyAndReturnsOriginalRoleDto() throws Exception {
        var service = mock(IRoleService.class);
        when(service.getById(1L)).thenReturn(new Role().setId(1L).setCode("merchant").setName("演示商家"));
        UserContext.setUser(10002L); RoleContext.set(1L);
        var mvc = MockMvcBuilders.standaloneSetup(new XucaiRoleController(service)).build();
        mvc.perform(get("/roles/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.name").value("演示商家"));
        mvc.perform(post("/roles")).andExpect(status().isNotFound());
        mvc.perform(put("/roles/1")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/roles/1")).andExpect(status().isMethodNotAllowed());
        verify(service).getById(1L);
        verifyNoMoreInteractions(service);
    }

    @Test void feignRelaysMerchantIdentityForInternalProfileLookup() throws Exception {
        UserContext.setUser(10002L); RoleContext.set(1L);
        var template = new RequestTemplate();
        new FeignRelayUserInterceptor().apply(template);
        clear();
        var request = new MockHttpServletRequest();
        template.headers().forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
        var response = new MockHttpServletResponse();
        var receiver = new UserInfoInterceptor();
        receiver.preHandle(request, response, new Object());
        assertTrue(new LoginAuthInterceptor().preHandle(request, response, new Object()));
        var service = mock(IRoleService.class);
        when(service.getById(1L)).thenReturn(new Role().setId(1L).setName("演示商家"));
        assertEquals("演示商家", new XucaiRoleController(service).queryRoleById(1L).getName());
        receiver.afterCompletion(request, response, new Object(), null);
        assertNull(UserContext.getUser()); assertNull(RoleContext.get());
    }
}
