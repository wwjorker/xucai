package com.xucai.auth;
import com.xucai.auth.controller.AccountController;
import com.xucai.auth.service.IAccountService;
import com.xucai.auth.service.ILoginRecordService;
import com.xucai.auth.service.impl.AccountServiceImpl;
import com.xucai.auth.util.JwtTool;
import com.xucai.api.client.user.UserClient;
import com.xucai.common.domain.dto.LoginUserDTO;
import com.xucai.common.exceptions.UnauthorizedException;
import com.xucai.common.utils.RoleContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SessionContractTest {
 @Test void refreshSelectsExplicitClientWithoutOrigin() {
  var service=mock(IAccountService.class); var controller=new AccountController(service);
  controller.refreshToken("buyer-token","merchant-token","buyer");
  controller.refreshToken("buyer-token","merchant-token","merchant");
  verify(service).refreshToken("buyer-token",false); verify(service).refreshToken("merchant-token",true);
  assertThrows(UnauthorizedException.class,()->controller.refreshToken(null,"merchant-token","buyer"));
 }
 @Test void wrongRoleCannotRotateOtherClientSession() {
  var jwt=mock(JwtTool.class); var user=new LoginUserDTO(); user.setRoleId(2L);
  when(jwt.parseRefreshToken("buyer")).thenReturn(user);
  var service=new AccountServiceImpl(jwt,mock(UserClient.class),mock(ILoginRecordService.class));
  assertThrows(UnauthorizedException.class,()->service.refreshToken("buyer",true));
  verify(jwt,never()).createToken(any());
 }
 @Test void merchantLogoutClearsMerchantCookieOnly() {
  var response=new MockHttpServletResponse();
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(),response));
  RoleContext.set(1L);
  try {
   var service=new AccountServiceImpl(mock(JwtTool.class),mock(UserClient.class),mock(ILoginRecordService.class));
   service.logout();
   assertEquals(0,response.getCookie("admin-refresh").getMaxAge()); assertNull(response.getCookie("refresh"));
  } finally { RequestContextHolder.resetRequestAttributes(); RoleContext.clear(); }
 }
 @Test void newAccessTokenExpiresInFiveMinutes() throws Exception {
  var generator=java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
  var jwt=new JwtTool(mock(StringRedisTemplate.class),generator.generateKeyPair());
  var user=new LoginUserDTO(); user.setUserId(10001L); user.setRoleId(2L);
  long expiry=((Number)cn.hutool.jwt.JWT.of(jwt.createToken(user)).getPayload("exp")).longValue();
  long remaining=expiry-System.currentTimeMillis()/1000;
  assertTrue(remaining>=298 && remaining<=300);
 }
}
