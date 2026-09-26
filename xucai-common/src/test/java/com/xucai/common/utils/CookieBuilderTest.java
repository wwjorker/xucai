package com.xucai.common.utils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
class CookieBuilderTest {
 @Test void localhostUsesHostOnlyHttpOnlyCookie() {
  var req=new MockHttpServletRequest(); req.setServerName("127.0.0.1");
  var res=new MockHttpServletResponse();
  new CookieBuilder(req,res).name("admin-refresh").value("example").httpOnly(true).build();
  var cookie=res.getCookie("admin-refresh");
  assertNotNull(cookie); assertNull(cookie.getDomain()); assertTrue(cookie.isHttpOnly());
  assertEquals("Lax",cookie.getAttribute("SameSite")); assertFalse(cookie.getSecure()); assertEquals("/",cookie.getPath());
 }
}
