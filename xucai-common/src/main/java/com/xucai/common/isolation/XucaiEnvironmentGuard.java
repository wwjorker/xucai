package com.xucai.common.isolation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import java.util.Arrays;
import java.util.Map;

/** 在创建业务连接之前核对应用数据库、注册分组和缓存边界。 */
public class XucaiEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
    private static final Map<String, String> DATABASES = Map.of(
            "xucai-auth", "xucai_auth", "xucai-user", "xucai_user",
            "xucai-catalog", "xucai_catalog", "xucai-trade", "xucai_trade",
            "xucai-ai", "xucai_ai");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.getProperty("xucai.isolation-required", Boolean.class, false)) return;
        require(Arrays.asList(env.getActiveProfiles()).contains("xucai"), "必须启用 xucai profile");
        require("XUCAI".equals(env.getProperty("spring.cloud.nacos.discovery.group")), "注册分组必须为 XUCAI");
        String app = env.getProperty("spring.application.name");
        if (Arrays.asList(env.getActiveProfiles()).contains("portable")) {
            require(!Arrays.asList(env.getActiveProfiles()).contains("local"), "portable不能混入旧local配置");
            require("false".equals(env.getProperty("spring.cloud.nacos.config.enabled")), "portable不读取Nacos配置");
            if ("xucai-auth".equals(app)) {
                require(env.getProperty("encrypt.key-store.location", "").startsWith("file:"), "portable必须使用本机独立签名密钥");
            }
        }
        String database = DATABASES.get(app);
        if (database != null) {
            require(database.equals(env.getProperty("xucai.jdbc.database")), "数据库名必须属于当前应用");
            String url = env.getProperty("spring.datasource.url", "");
            require(url.matches("jdbc:mysql://[^/]+/" + database + "(?:\\?.*)?"), "实际 JDBC URL 未指向应用专用库");
            // Connector/J attempts to open even an empty key filename. Omit only the empty optional
            // property; preserve an explicitly configured key and never enable public-key retrieval.
            if (Arrays.asList(env.getActiveProfiles()).contains("portable")) {
                String normalized = url.replaceAll("&serverRSAPublicKeyFile=(?=&|$)", "");
                if (!normalized.equals(url)) env.getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("xucaiOptionalPublicKey", Map.of("spring.datasource.url", normalized)));
            }
        }
        boolean ai = "xucai-ai".equals(app);
        require(env.getProperty("spring.data.redis.port", Integer.class, -1) == 16379, "必须连接已核对的 Redis Stack 实例");
        require(env.getProperty("spring.data.redis.database", Integer.class, -1) == (ai ? 0 : 15), "Redis 逻辑库未隔离");
        if (ai) {
            for (String prefix : new String[]{"spring.ai.dashscope", "spring.ai.dashscope.chat", "spring.ai.dashscope.embedding"}) {
                require("http://127.0.0.1:9".equals(env.getProperty(prefix + ".base-url")), "Spring AI 自动配置的模型地址必须为 http://127.0.0.1:9，模型调用须通过采购模块配置");
            }
            require("false".equals(env.getProperty("spring.ai.vectorstore.redis.initialize-schema")), "spring.ai.vectorstore.redis.initialize-schema 必须为 false，禁止自动初始化向量索引");
            for (String key : new String[]{"xucai.ai.memory.key-prefix", "xucai.ai.generate-status-key", "spring.ai.vectorstore.redis.prefix"}) {
                require(env.getProperty(key, "").startsWith("xucai:"), "AI Redis 键未隔离：" + key);
            }
            require("xucai-knowledge".equals(env.getProperty("spring.ai.vectorstore.redis.index-name")), "向量索引未隔离");
        }
        System.out.printf("[xucai-boundary] app=%s database=%s redis-db=%s discovery-group=XUCAI legacy-model-api=false procurement-model-enabled=%s%n",
                app, database == null ? "none" : database, env.getProperty("spring.data.redis.database"),
                ai && env.getProperty("xucai.procurement.model.enabled",Boolean.class,false));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("采购平台隔离检查失败：" + message);
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
