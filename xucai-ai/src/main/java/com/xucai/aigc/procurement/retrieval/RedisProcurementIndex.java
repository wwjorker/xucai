package com.xucai.aigc.procurement.retrieval;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import redis.clients.jedis.*;
import redis.clients.jedis.search.*;
import redis.clients.jedis.search.schemafields.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.xucai.aigc.procurement.retrieval.DashScopeEmbeddingClient.error;

/** 仅接收已显式计量的向量，存储/查询不会暗中调用模型。 */
@Component @Profile("xucai")
@ConditionalOnProperty(name="xucai.procurement.retrieval.enabled",havingValue="true")
public class RedisProcurementIndex implements VectorIndex {
    static final String INDEX="xucai-knowledge",PREFIX="xucai:vector:";
    private final JedisPooled jedis;
    public RedisProcurementIndex(@Value("${spring.data.redis.host}") String host,@Value("${spring.data.redis.port}") int port,@Value("${spring.data.redis.password}") String password) {
        if(port!=16379)throw new IllegalStateException("采购向量必须使用已隔离Redis Stack端口");
        this.jedis=new JedisPooled(new HostAndPort(host,port),DefaultJedisClientConfig.builder().password(password).database(0).connectionTimeoutMillis(2000).socketTimeoutMillis(3000).build());
    }
    @PreDestroy public void close(){jedis.close();}
    @Override public void ensureSchema() {
        // 不删除、不覆盖同名未知索引。允许并发create，随后统一校验实际schema。
        if(!jedis.ftList().contains(INDEX)) {
            try {jedis.ftCreate(INDEX,FTCreateParams.createParams().on(IndexDataType.HASH).prefix(PREFIX),List.of(
                    TagField.of("generation"),VectorField.builder().fieldName("embedding").algorithm(VectorField.VectorAlgorithm.FLAT)
                            .attributes(Map.of("TYPE","FLOAT32","DIM",EmbeddingClient.DIMENSIONS,"DISTANCE_METRIC","COSINE")).build()));}
            catch(redis.clients.jedis.exceptions.JedisDataException e){if(!jedis.ftList().contains(INDEX))throw error("RETRIEVAL_STORAGE");}
        }
        verifySchema(jedis.ftInfo(INDEX));
    }
    static Map<String,Object> pairs(Object input) {
        if(input instanceof Map<?,?> map){Map<String,Object> out=new HashMap<>();map.forEach((k,v)->out.put(k.toString(),v));return out;}
        if(!(input instanceof List<?> list) || list.size()%2!=0)throw error("RETRIEVAL_SCHEMA");
        Map<String,Object> out=new HashMap<>();for(int i=0;i<list.size();i+=2)out.put(list.get(i).toString(),list.get(i+1));return out;
    }
    static void verifySchema(Map<String,Object> info) {
        var def=pairs(info.get("index_definition"));
        if(!"HASH".equals(def.get("key_type")) || !List.of(PREFIX).equals(def.get("prefixes")))throw error("RETRIEVAL_SCHEMA");
        if(!(info.get("attributes") instanceof List<?> attributes))throw error("RETRIEVAL_SCHEMA");
        boolean generation=false,vector=false;
        for(var a:attributes){var f=pairs(a);if("generation".equals(f.get("attribute")))generation="generation".equals(f.get("identifier"))&&"TAG".equals(f.get("type"));
            if("embedding".equals(f.get("attribute")))vector="embedding".equals(f.get("identifier"))&&"VECTOR".equals(f.get("type"))&&"FLAT".equals(f.get("algorithm"))&&"FLOAT32".equals(f.get("data_type"))&&"1024".equals(String.valueOf(f.get("dim")))&&"COSINE".equals(f.get("distance_metric"));}
        if(!generation||!vector)throw error("RETRIEVAL_SCHEMA");
    }
    static String tag(String generation){if(generation==null||!generation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw error("RETRIEVAL_GENERATION");return generation.replace("-","");}
    @Override public String key(String generation,String chunkId){tag(generation);if(chunkId==null||!chunkId.matches("doc:[1-9][0-9]*:v[1-9][0-9]*:c[1-9][0-9]*"))throw error("RETRIEVAL_CHUNK");return PREFIX+generation+":"+chunkId;}
    static byte[] bytes(float[] vector){if(vector==null||vector.length!=EmbeddingClient.DIMENSIONS)throw error("EMBEDDING_DIMENSIONS");var out=ByteBuffer.allocate(vector.length*4).order(ByteOrder.LITTLE_ENDIAN);double norm=0;for(var n:vector){if(!Float.isFinite(n))throw error("EMBEDDING_OUTPUT");norm+=(double)n*n;out.putFloat(n);}if(norm==0)throw error("EMBEDDING_OUTPUT");return out.array();}
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String checksum(byte[] data){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void put(String generation,String chunkId,float[] vector){var binary=bytes(vector);jedis.hset(utf(key(generation,chunkId)),Map.of(utf("generation"),utf(tag(generation)),utf("embedding"),binary,utf("checksum"),utf(checksum(binary))));}
    @Override public long count(String generation){return jedis.ftSearch(INDEX,new Query("@generation:{"+tag(generation)+"}").limit(0,0).dialect(2)).getTotalResults();}
    @Override public boolean complete(String generation,List<String> chunks){
        if(chunks.isEmpty()||chunks.size()>200||new HashSet<>(chunks).size()!=chunks.size()||count(generation)!=chunks.size())return false;
        for(var chunk:chunks){var row=jedis.hmget(utf(key(generation,chunk)),utf("generation"),utf("embedding"),utf("checksum"));
            if(row.size()!=3||!Arrays.equals(row.get(0),utf(tag(generation)))||row.get(1)==null||row.get(1).length!=EmbeddingClient.DIMENSIONS*4||!Arrays.equals(row.get(2),utf(checksum(row.get(1)))))return false;
            var floats=ByteBuffer.wrap(row.get(1)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] vector=new float[EmbeddingClient.DIMENSIONS];floats.get(vector);
            try{bytes(vector);}catch(RuntimeException e){return false;}
        }return true;
    }
    @Override public List<Hit> search(List<String> generations,float[] vector,int count){
        if(generations.isEmpty()||generations.size()>20||count<1||count>20)throw error("RETRIEVAL_INPUT");
        String tags=String.join("|",generations.stream().map(RedisProcurementIndex::tag).toList());
        var query=new Query("(@generation:{"+tags+"})=>[KNN "+count+" @embedding $vector AS distance]").addParam("vector",bytes(vector)).returnFields("distance").setSortBy("distance",true).limit(0,count).dialect(2);
        return jedis.ftSearch(INDEX,query).getDocuments().stream().map(d->new Hit(d.getId(),Double.parseDouble(d.getString("distance")))).toList();
    }
}
