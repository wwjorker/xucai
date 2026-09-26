package com.xucai.gateway;
import com.xucai.gateway.config.AuthProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortableAuthPropertiesTest {
    @Test void missingExternalWhitelistDoesNotCrashOrOpenAllPaths() throws Exception {
        var properties=new AuthProperties();properties.afterPropertiesSet();
        assertTrue(properties.getExcludePath().contains("/jwks"));
        assertFalse(properties.getExcludePath().contains("/**"));
        assertEquals(5,properties.getExcludePath().size());
    }
}
