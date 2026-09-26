package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.aigc.procurement.ModelCallException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashScopeEmbeddingClientTest {
    @Test void personalQueryUsesSeparateReservationBeforeSingleTransport()throws Exception{
        var ledger=mock(EmbeddingLedger.class);var transport=mock(DashScopeEmbeddingClient.Transport.class);
        when(transport.send(anyString())).thenReturn(new DashScopeEmbeddingClient.Response(200,response(List.of(row(0,1024,.25)))));
        String personal="00000000-0000-4000-8000-000000000001:101";
        new DashScopeEmbeddingClient(ledger,mapper,transport).embedAssistant(personal,"蓝牙");
        var order=inOrder(ledger,transport);order.verify(ledger).reserveAssistant(personal,6,4096);order.verify(transport).send(anyString());order.verify(ledger).finish(personal,8,null);
        verify(ledger,never()).reserve(anyString(),anyString(),anyInt(),anyInt(),anyLong());
    }
    final ObjectMapper mapper=new ObjectMapper();
    final String id="00000000-0000-4000-8000-000000000001:0";
    Map<String,Object> row(int index,int dimensions,double value){var vector=new ArrayList<Double>();for(int i=0;i<dimensions;i++)vector.add(value);return Map.of("text_index",index,"embedding",vector);}
    String response(List<Map<String,Object>> rows)throws Exception{return mapper.writeValueAsString(Map.of("output",Map.of("embeddings",rows),"usage",Map.of("total_tokens",8)));}
    @Test void requestIsPrivateAndBudgetPrecedesTransport()throws Exception {
        var ledger=mock(EmbeddingLedger.class);var transport=mock(DashScopeEmbeddingClient.Transport.class);
        when(transport.send(anyString())).thenReturn(new DashScopeEmbeddingClient.Response(200,response(List.of(row(0,1024,.25)))));
        var client=new DashScopeEmbeddingClient(ledger,mapper,transport);assertEquals(1024,client.embed(id,"query",List.of("有线键盘")).get(0).length);
        var order=inOrder(ledger,transport);order.verify(ledger).reserve(id,"query",1,12,4096);order.verify(transport).send(anyString());order.verify(ledger).finish(id,8,null);
        var body=org.mockito.ArgumentCaptor.forClass(String.class);verify(transport).send(body.capture());var json=mapper.readTree(body.getValue());
        assertEquals("query",json.path("parameters").path("text_type").asText());assertEquals(1024,json.path("parameters").path("dimension").asInt());assertEquals(Set.of("model","input","parameters"),mapper.convertValue(json,Map.class).keySet());
    }
    @Test void documentTypeIsExplicit()throws Exception {
        var transport=mock(DashScopeEmbeddingClient.Transport.class);when(transport.send(anyString())).thenReturn(new DashScopeEmbeddingClient.Response(200,response(List.of(row(0,1024,.25)))));
        new DashScopeEmbeddingClient(mock(EmbeddingLedger.class),mapper,transport).embed(id,"document",List.of("原文"));
        verify(transport).send(argThat(s->s.contains("\"text_type\":\"document\"")));
    }
    @Test void budgetFailureNeverSends()throws Exception {
        var ledger=mock(EmbeddingLedger.class);doThrow(new ModelCallException("LIMIT","limit")).when(ledger).reserve(anyString(),anyString(),anyInt(),anyInt(),anyLong());var transport=mock(DashScopeEmbeddingClient.Transport.class);
        assertThrows(ModelCallException.class,()->new DashScopeEmbeddingClient(ledger,mapper,transport).embed(id,"query",List.of("test")));verifyNoInteractions(transport);
    }
    @Test void timeoutIsNotRetriedAndReservationIsNotReleased()throws Exception {
        var ledger=mock(EmbeddingLedger.class);var transport=mock(DashScopeEmbeddingClient.Transport.class);when(transport.send(anyString())).thenThrow(new java.net.http.HttpTimeoutException("not logged"));
        assertThrows(ModelCallException.class,()->new DashScopeEmbeddingClient(ledger,mapper,transport).embed(id,"document",List.of("abc")));verify(transport,times(1)).send(anyString());verify(ledger).finish(id,null,"EMBEDDING_UNAVAILABLE");
    }
    @Test void redirectNeverAdoptedAsSuccess()throws Exception {
        var ledger=mock(EmbeddingLedger.class);var transport=mock(DashScopeEmbeddingClient.Transport.class);when(transport.send(anyString())).thenReturn(new DashScopeEmbeddingClient.Response(307,"secret response"));
        var e=assertThrows(ModelCallException.class,()->new DashScopeEmbeddingClient(ledger,mapper,transport).embed(id,"query",List.of("test")));assertFalse(e.getMessage().contains("secret"));verify(transport,times(1)).send(anyString());
    }
    @Test void validatesBatchAndBytesBeforeLedger() {
        var ledger=mock(EmbeddingLedger.class);var client=new DashScopeEmbeddingClient(ledger,mapper,body->{throw new AssertionError("not called");});
        assertThrows(ModelCallException.class,()->client.embed(id,"document",Collections.nCopies(11,"x")));
        assertThrows(ModelCallException.class,()->client.embed(id,"query",List.of("x","y")));
        assertThrows(ModelCallException.class,()->client.embed(id,"document",List.of("中".repeat(1400))));
        assertThrows(ModelCallException.class,()->client.embed(id,"document",List.of(" ")));verifyNoInteractions(ledger);
    }
    @Test void reorderedResponseUsesTextIndex()throws Exception {var result=DashScopeEmbeddingClient.parse(mapper.readTree(response(List.of(row(1,1024,.5),row(0,1024,.25)))),2);assertEquals(.25f,result.get(0)[0]);assertEquals(.5f,result.get(1)[0]);}
    @Test void duplicateIndexRejected()throws Exception {var json=mapper.readTree(response(List.of(row(0,1024,.5),row(0,1024,.25))));assertThrows(ModelCallException.class,()->DashScopeEmbeddingClient.parse(json,2));}
    @Test void missingRowRejected()throws Exception {var json=mapper.readTree(response(List.of(row(0,1024,.5))));assertThrows(ModelCallException.class,()->DashScopeEmbeddingClient.parse(json,2));}
    @Test void wrongDimensionsRejected()throws Exception {var json=mapper.readTree(response(List.of(row(0,768,.5))));assertThrows(ModelCallException.class,()->DashScopeEmbeddingClient.parse(json,1));}
    @Test void zeroAndNonfiniteVectorsRejected()throws Exception {for(double v:new double[]{0,Double.NaN,Double.POSITIVE_INFINITY}){var json=mapper.readTree(response(List.of(row(0,1024,v))));assertThrows(ModelCallException.class,()->DashScopeEmbeddingClient.parse(json,1));}}
    @Test void oversizedOrMissingUsageIsFailure()throws Exception {
        var ledger=mock(EmbeddingLedger.class);var transport=mock(DashScopeEmbeddingClient.Transport.class);when(transport.send(anyString())).thenReturn(new DashScopeEmbeddingClient.Response(200,"{\"usage\":{\"total_tokens\":8193}}"));
        var error=assertThrows(ModelCallException.class,()->new DashScopeEmbeddingClient(ledger,mapper,transport).embed(id,"query",List.of("x")));assertEquals("EMBEDDING_USAGE",error.code());
    }
}
