package com.xucai.common.isolation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class XucaiEnvironmentGuardTest {
    @Test void optionalEmptyPublicKeyIsOmittedWithoutEnablingRetrieval() {
        MockEnvironment env=safeEnvironment();env.setActiveProfiles("portable","xucai");env.setProperty("spring.cloud.nacos.config.enabled","false");
        env.setProperty("spring.datasource.url","jdbc:mysql://localhost:3306/xucai_user?useSSL=false&serverRSAPublicKeyFile=&serverTimezone=Asia/Shanghai");
        new XucaiEnvironmentGuard().postProcessEnvironment(env,null);
        assertEquals("jdbc:mysql://localhost:3306/xucai_user?useSSL=false&serverTimezone=Asia/Shanghai",env.getProperty("spring.datasource.url"));
    }
    @Test void explicitPublicKeyIsPreserved() {
        MockEnvironment env=safeEnvironment();env.setActiveProfiles("portable","xucai");env.setProperty("spring.cloud.nacos.config.enabled","false");
        String url="jdbc:mysql://localhost:3306/xucai_user?useSSL=false&serverRSAPublicKeyFile=F:/trusted/public.pem";
        env.setProperty("spring.datasource.url",url);new XucaiEnvironmentGuard().postProcessEnvironment(env,null);
        assertEquals(url,env.getProperty("spring.datasource.url"));
    }
    @Test
    void portableRefusesLegacyProfilesAndRemoteConfiguration() {
        MockEnvironment env=safeEnvironment();env.setActiveProfiles("portable","xucai");
        env.setProperty("spring.cloud.nacos.config.enabled","false");
        assertDoesNotThrow(()->new XucaiEnvironmentGuard().postProcessEnvironment(env,null));
        env.setActiveProfiles("portable","local","xucai");
        assertThrows(IllegalStateException.class,()->new XucaiEnvironmentGuard().postProcessEnvironment(env,null));
        env.setActiveProfiles("portable","xucai");env.setProperty("spring.cloud.nacos.config.enabled","true");
        assertThrows(IllegalStateException.class,()->new XucaiEnvironmentGuard().postProcessEnvironment(env,null));
    }
    @Test
    void refusesLaunchThatOmitsIsolationProfile() {
        MockEnvironment env = safeEnvironment();
        env.setActiveProfiles("local");
        assertThrows(IllegalStateException.class, () -> new XucaiEnvironmentGuard().postProcessEnvironment(env, null));
    }

    @Test
    void refusesDatasourcePointingToAnotherApplication() {
        MockEnvironment env = safeEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/unrelated_database");
        assertThrows(IllegalStateException.class, () -> new XucaiEnvironmentGuard().postProcessEnvironment(env, null));
    }

    @Test
    void acceptsIsolatedConfigurationWithoutUnusedInfrastructureFlags() {
        MockEnvironment env = safeEnvironment();
        assertDoesNotThrow(() -> new XucaiEnvironmentGuard().postProcessEnvironment(env, null));

    }

    private MockEnvironment safeEnvironment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("xucai.isolation-required", "true")
                .withProperty("spring.application.name", "xucai-user")
                .withProperty("spring.cloud.nacos.discovery.group", "XUCAI")
                .withProperty("xucai.jdbc.database", "xucai_user")
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/xucai_user?useSSL=false")
                .withProperty("spring.data.redis.database", "15");
        env.setProperty("spring.data.redis.port", "16379");
        env.setActiveProfiles("local", "xucai");
        return env;
    }
}
