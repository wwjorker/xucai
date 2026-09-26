package com.xucai.aigc.procurement.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalIsolationTest {
    @Test void legacyEmbeddingRoutesRemovedFromArtifact(){assertThrows(ClassNotFoundException.class,()->Class.forName("com.xucai.aigc.controller.EmbeddingController"));}
    @Test void privateAdaptersDoNotImplementLegacyGlobalTypes(){assertFalse(EmbeddingModel.class.isAssignableFrom(DashScopeEmbeddingClient.class));assertFalse(VectorStore.class.isAssignableFrom(RedisProcurementIndex.class));}
    @Test void floatsAreLittleEndianAndWrongDimensionRejected(){float[] v=new float[1024];v[0]=1;assertEquals(1,ByteBuffer.wrap(RedisProcurementIndex.bytes(v)).order(ByteOrder.LITTLE_ENDIAN).getFloat());assertThrows(RuntimeException.class,()->RedisProcurementIndex.bytes(new float[768]));}
    @Test void generationCannotInjectRedisFilter(){assertEquals("00000000000040008000000000000001",RedisProcurementIndex.tag(RetrievalServiceTest.ID));assertThrows(RuntimeException.class,()->RedisProcurementIndex.tag("x}) | *"));}
    @Test void schemaRejectsMismatchedHashFieldAlias(){var fields=List.of(List.of("identifier","other","attribute","generation","type","TAG"),List.of("identifier","embedding","attribute","embedding","type","VECTOR","algorithm","FLAT","data_type","FLOAT32","dim",1024L,"distance_metric","COSINE"));assertThrows(RuntimeException.class,()->RedisProcurementIndex.verifySchema(Map.of("index_definition",List.of("key_type","HASH","prefixes",List.of("xucai:vector:")),"attributes",fields)));}
    @Test void schemaRejectsForeignPrefixAndWrongDimension(){var fields=List.of(List.of("identifier","generation","attribute","generation","type","TAG"),List.of("identifier","embedding","attribute","embedding","type","VECTOR","algorithm","FLAT","data_type","FLOAT32","dim",1024L,"distance_metric","COSINE"));var definition=List.of("key_type","HASH","prefixes",List.of("xucai:vector:"));RedisProcurementIndex.verifySchema(Map.of("index_definition",definition,"attributes",fields));assertThrows(RuntimeException.class,()->RedisProcurementIndex.verifySchema(Map.of("index_definition",List.of("key_type","JSON","prefixes",List.of("doc:")),"attributes",fields)));}
}
