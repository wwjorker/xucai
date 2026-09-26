package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的真实Redis合同验证；人工向量不是模型/检索质量验收。仅删除本测试新UUID的两个键。 */
@EnabledIfEnvironmentVariable(named="XUCAI_REDIS_CONTRACT_TEST",matches="true")
class RedisProcurementContractTest {
    @Test void actualSchemaKnnAndMissingVectorDetection()throws Exception {
        String host=System.getenv("XUCAI_REDIS_HOST");if(host==null)host="127.0.0.1";
        String password=System.getenv("XUCAI_REDIS_PASSWORD");
        String generation=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        var index=new RedisProcurementIndex(host,16379,password);
        try(var redis=new JedisPooled(new HostAndPort(host,16379),DefaultJedisClientConfig.builder().password(password).database(0).build())) {
            var oldIndexes=redis.ftList();index.ensureSchema();
            float[] first=new float[1024],second=new float[1024];first[0]=1;second[1]=1;
            String a=index.key(generation,"doc:900000001:v1:c1"),b=index.key(other,"doc:900000002:v1:c1");
            assertFalse(redis.exists(a));assertFalse(redis.exists(b));
            try {
                index.put(generation,"doc:900000001:v1:c1",first);index.put(other,"doc:900000002:v1:c1",second);
                assertTrue(index.complete(generation,List.of("doc:900000001:v1:c1")));
                var hits=index.search(List.of(generation),first,2);assertEquals(1,hits.size());assertEquals(a,hits.get(0).key());assertEquals(0,hits.get(0).distance(),.00001);
                redis.hdel(a,"embedding");assertEquals(1,index.count(generation));assertFalse(index.complete(generation,List.of("doc:900000001:v1:c1")));
                index.put(generation,"doc:900000001:v1:c1",first);redis.hset(a,"checksum","bad");assertFalse(index.complete(generation,List.of("doc:900000001:v1:c1")));
                index.put(generation,"doc:900000001:v1:c1",first);assertTrue(index.complete(generation,List.of("doc:900000001:v1:c1")));
                assertTrue(redis.ftList().containsAll(oldIndexes));
                Files.createDirectories(Path.of("target"));new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/redis-retrieval-contract.json").toFile(),Map.of("schema","HASH/FLAT/FLOAT32/1024/COSINE","generationFilter",true,"missingVectorDetected",true,"checksumDamageDetected",true,"syntheticVectors",true,"modelCalls",0,"existingIndexNamesPreserved",true));
            } finally {redis.del(a,b);assertFalse(redis.exists(a));assertFalse(redis.exists(b));}
        } finally {index.close();}
    }
}
