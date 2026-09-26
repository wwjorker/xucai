package com.xucai.catalog.service;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.common.exceptions.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ProductImageStore {
    private final String configPath;
    private volatile OSS client;
    private String bucket;
    public ProductImageStore(@Value("${xucai.oss.config-file:}") String configPath) { this.configPath = configPath; }
    private synchronized OSS client() {
        if (client != null) return client;
        if (configPath.isBlank()) throw new BusinessException(503,"商品图片存储尚未配置");
        try {
            var cfg = new ObjectMapper().readTree(Path.of(configPath).toFile());
            String region = cfg.path("region").asText(), endpoint = cfg.path("endpoint").asText();
            bucket = cfg.path("bucket").asText();
            if (!region.matches("cn-[a-z0-9-]+") || !endpoint.equals("https://oss-" + region + ".aliyuncs.com")
                    || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") || !cfg.path("prefix").asText().equals("products/"))
                throw new IllegalArgumentException();
            String id = cfg.path("accessKeyId").asText().trim(), secret = cfg.path("accessKeySecret").asText().trim();
            if (id.isBlank() || secret.isBlank()) throw new IllegalArgumentException();
            var options = new ClientBuilderConfiguration();
            options.setSignatureVersion(SignVersion.V4);
            options.setMaxErrorRetry(0);
            options.setConnectionTimeout(3000);
            options.setSocketTimeout(7000);
            options.setMaxConnections(10);
            client = OSSClientBuilder.create().endpoint(endpoint).region(region)
                    .credentialsProvider(new DefaultCredentialProvider(id, secret)).clientConfiguration(options).build();
            return client;
        } catch (Exception e) { throw new BusinessException(503,"商品图片存储配置不可用，请联系维护者"); }
    }
    public String put(ProductImageData image) {
        String key = "products/" + UUID.randomUUID() + "." + image.extension();
        OSS oss = client();
        try {
            var metadata = new ObjectMetadata();
            metadata.setContentLength(image.bytes().length);
            metadata.setContentType(image.contentType());
            metadata.setHeader("x-oss-forbid-overwrite", "true");
            oss.putObject(bucket, key, new ByteArrayInputStream(image.bytes()), metadata);
            return key;
        } catch (Exception e) { throw new BusinessException(503,"图片上传未确认成功，请刷新核对，不要连续重试"); }
    }
    public ProductImageData get(String key) {
        if (!ProductImageRef.validKey(key)) throw new BusinessException(404,"商品图片不存在");
        OSS oss = client();
        try (var object = oss.getObject(bucket, key)) {
            byte[] bytes = object.getObjectContent().readNBytes(ProductImageData.MAX_OUTPUT + 1);
            if (bytes.length > ProductImageData.MAX_OUTPUT) throw new IOException();
            String ext = key.endsWith(".png") ? "png" : "jpg";
            return new ProductImageData(bytes, ext, ext.equals("png") ? "image/png" : "image/jpeg");
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) throw new BusinessException(404,"商品图片不存在");
            throw new BusinessException(503,"商品图片暂不可用");
        } catch (Exception e) { throw new BusinessException(503,"商品图片暂不可用"); }
    }
    @PreDestroy public void close() { if (client != null) client.shutdown(); }
}
