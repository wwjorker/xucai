package com.xucai.aigc.procurement;

import com.xucai.api.client.trade.RequirementClient;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRunServiceTest {
    private final Validator validator=Validation.buildDefaultValidatorFactory().getValidator();
    private final MemoryStore store=new MemoryStore();
    private final RequirementClient client=mock(RequirementClient.class);
    private AnalysisRunService service;
    private final RequirementDetails details=new RequirementDetails(null,null,List.of(),"",List.of(),List.of());
    private RequirementDTO input(long user,long revision){return new RequirementDTO(user,user,"测试需求","预算待确认",details,revision,"OPEN","now","now");}
    private AnalysisResult result(){return new AnalysisResult(details,List.of("请确认总预算"));}
    private void setup(RequirementAnalyzer analyzer,long timeout){setup(analyzer,timeout,new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1)));}
    private void setup(RequirementAnalyzer analyzer,long timeout,ExecutorService pool){
        when(client.get(anyLong(),anyLong(),eq(2L))).thenAnswer(i->input(i.getArgument(1),1));
        service=new AnalysisRunService(store,client,analyzer,validator,pool,Executors.newSingleThreadScheduledExecutor(),timeout);service.initialize();
    }
    private void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(condition.getAsBoolean());}
    @AfterEach void close(){if(service!=null)service.close();}

    @Test void disabledProviderPersistsFailureAndDoesNotChangeRequirement()throws Exception{
        setup(new DisabledRequirementAnalyzer(),5000);RunDTO run=service.start(1,1,1);
        await(()->!store.get(run.id(),1).status().equals("RUNNING"));
        assertEquals("MODEL_DISABLED",store.get(run.id(),1).errorCode());assertFalse(service.available());
        verify(client).get(1L,1L,2L);verifyNoMoreInteractions(client);
    }
    @Test void doneKeepsSnapshotAndCannotBeCanceled()throws Exception{
        AtomicReference<RequirementDTO> seen=new AtomicReference<>();setup(in->{seen.set(in);return result();},5000);
        RunDTO run=service.start(1,1,1);await(()->store.get(run.id(),1).status().equals("DONE"));
        RunDTO done=service.cancel(run.id(),1);assertEquals("DONE",done.status());assertNotNull(done.result());
        assertEquals(input(1,1),seen.get());verify(client,times(2)).get(1L,1L,2L);
        assertThrows(BusinessException.class,()->service.get(run.id(),2));
    }
    @Test void cancellationBeatsLateCompletionEvenWhenAnalyzerIgnoresInterrupt()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        setup(in->{started.countDown();boolean done=false;while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}returned.countDown();return result();},5000);
        RunDTO run=service.start(1,1,1);assertTrue(started.await(2,TimeUnit.SECONDS));
        assertEquals("CANCELED",service.cancel(run.id(),1).status());release.countDown();assertTrue(returned.await(2,TimeUnit.SECONDS));
        await(()->store.get(run.id(),1).status().equals("CANCELED"));assertNull(store.get(run.id(),1).result());
    }
    @Test void editsDuringAnalysisRejectStaleResult()throws Exception{
        setup(in->{when(client.get(1L,1L,2L)).thenReturn(input(1,2));return result();},5000);
        RunDTO run=service.start(1,1,1);await(()->store.get(run.id(),1).status().equals("FAILED"));
        assertEquals("STALE_REQUIREMENT",store.get(run.id(),1).errorCode());assertEquals(1L,store.get(run.id(),1).input().revision());
    }
    @Test void deadlineInterruptsBlockedAnalysisAndLeavesNoResult()throws Exception{
        setup(in->{try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return result();},80);
        RunDTO run=service.start(1,1,1);await(()->store.get(run.id(),1).status().equals("FAILED"));
        assertEquals("TIMEOUT",store.get(run.id(),1).errorCode());assertNull(store.get(run.id(),1).result());
    }
    @Test void rejectedExecutorPersistsFailure(){
        ExecutorService rejected=Executors.newSingleThreadExecutor();rejected.shutdown();setup(in->result(),5000,rejected);
        RunDTO run=service.start(1,1,1);assertEquals("FAILED",run.status());assertEquals("BUSY",run.errorCode());
    }
    @Test void canceledQueuedTaskReleasesQueueSlot()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
        setup(in->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return result();},5000,pool);
        service.start(1,1,1);assertTrue(started.await(2,TimeUnit.SECONDS));
        RunDTO queued=service.start(2,2,1);assertEquals(1,pool.getQueue().size());service.cancel(queued.id(),2);
        assertEquals(0,pool.getQueue().size());assertEquals("RUNNING",service.start(3,3,1).status());release.countDown();
    }
    @Test void transientDatabaseFailureRetriesOriginalTerminalResult()throws Exception{
        store.failures.set(2);setup(in->result(),5000);RunDTO run=service.start(1,1,1);
        await(()->store.get(run.id(),1).status().equals("DONE"));assertNotNull(store.get(run.id(),1).result());
        assertTrue(store.finishCalls.get()>=3);
    }
    @Test void timeoutPersistenceAlsoRetriesAfterDatabaseRecovers()throws Exception{
        store.failures.set(2);setup(in->{try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return result();},80);
        RunDTO run=service.start(1,1,1);await(()->store.get(run.id(),1).status().equals("FAILED"));assertEquals("TIMEOUT",store.get(run.id(),1).errorCode());
    }
    @Test void deadlineInterruptsImmediatelyWhileTerminalDatabaseWriteFails()throws Exception{
        CountDownLatch interrupted=new CountDownLatch(1);AtomicInteger nextStage=new AtomicInteger();
        setup(in->{try{Thread.sleep(10000);nextStage.incrementAndGet();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return result();},100);
        store.failures.set(100);var run=service.start(1,1,1);
        assertTrue(interrupted.await(2,TimeUnit.SECONDS));assertEquals(0,nextStage.get());assertEquals("RUNNING",store.get(run.id(),1).status());
        store.failures.set(0);await(()->"FAILED".equals(store.get(run.id(),1).status()));assertEquals("TIMEOUT",store.get(run.id(),1).errorCode());
    }
    @Test void startupRecoversEveryRunningRecordIncludingFreshOnes(){
        store.insert("fresh",input(1,1),"test");store.insert("old",input(2,1),"test");store.finish("old","DONE",result(),null,null);
        setup(in->result(),5000);assertEquals("PROCESS_RESTARTED",store.get("fresh",1).errorCode());assertEquals("DONE",store.get("old",2).status());
    }
    @Test void unknownBudgetQuantityAllowedButNullCategoryAndEmptyConditionRejected(){
        assertTrue(validator.validate(details).isEmpty());
        assertFalse(validator.validate(new RequirementDetails(null,null,Arrays.asList((Integer)null),"",List.of(),List.of())).isEmpty());
        assertFalse(validator.validate(new RequirementDetails(null,null,List.of(),"",List.of(" "),List.of())).isEmpty());
    }
    @Test void oldOrClosedRequirementCannotStart(){
        setup(in->result(),5000);assertThrows(BusinessException.class,()->service.start(1,1,2));assertTrue(store.rows.isEmpty());
    }
    private RequirementDTO candidateInput(){return new RequirementDTO(1L,1L,"候选需求","办公",new RequirementDetails(1000L,1,List.of(1),"办公",List.of(),List.of()),1L,"OPEN","now","now");}
    @Test void candidateUsesSameOwnerAndTerminalPipeline()throws Exception{
        var proposal=new CandidateProposal("NO_SELECTION",List.of(),0,1,false,false,"有限范围参考");
        setup(new RequirementAnalyzer(){public AnalysisResult analyze(RequirementDTO in){throw new AssertionError("must not use extraction");}
            public AnalysisResult candidates(RequirementDTO in,String runId){return new AnalysisResult(in.structured(),List.of(),List.of(),proposal);}},5000);
        when(client.get(1L,1L,2L)).thenReturn(candidateInput());
        var run=service.start(1,1,1,true);await(()->store.get(run.id(),1).status().equals("DONE"));
        assertEquals("candidate-reference-v8",store.get(run.id(),1).promptVersion());
        assertEquals(proposal,store.get(run.id(),1).result().candidate());
        assertThrows(BusinessException.class,()->service.get(run.id(),2));
    }
    @Test void candidateWithoutSavedQuantitiesDoesNotInsertRun(){
        setup(in->result(),5000);assertThrows(BusinessException.class,()->service.start(1,1,1,true));assertTrue(store.rows.isEmpty());
    }
    @Test void candidateValidationFailurePersistsSafeCodeWithoutResultOrRetry()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        setup(new RequirementAnalyzer(){public AnalysisResult analyze(RequirementDTO in){throw new AssertionError();}
            public AnalysisResult candidates(RequirementDTO in,String runId){calls.incrementAndGet();throw CandidatePlanner.fail("CANDIDATE_CATEGORY_DUPLICATE","模型在同一设备分类选择了多款商品，未采用");}},5000);
        when(client.get(1L,1L,2L)).thenReturn(candidateInput());
        var run=service.start(1,1,1,true);await(()->"FAILED".equals(store.get(run.id(),1).status()));
        var failed=service.get(run.id(),1);assertEquals("CANDIDATE_CATEGORY_DUPLICATE",failed.errorCode());
        assertNull(failed.result());assertEquals(1,calls.get());assertEquals(candidateInput(),failed.input());
        assertEquals("FAILED",service.cancel(run.id(),1).status());assertEquals(1,calls.get());
        verify(client).get(1L,1L,2L);verifyNoMoreInteractions(client);
    }
    @Test void canceledCandidateCannotPersistLateResult()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        setup(new RequirementAnalyzer(){public AnalysisResult analyze(RequirementDTO in){throw new AssertionError();}
            public AnalysisResult candidates(RequirementDTO in,String runId){started.countDown();boolean done=false;while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}returned.countDown();return new AnalysisResult(in.structured(),List.of(),List.of(),new CandidateProposal("NO_SELECTION",List.of(),0,1,false,false,"参考"));}},5000);
        when(client.get(1L,1L,2L)).thenReturn(candidateInput());var run=service.start(1,1,1,true);assertTrue(started.await(2,TimeUnit.SECONDS));
        service.cancel(run.id(),1);release.countDown();assertTrue(returned.await(2,TimeUnit.SECONDS));assertEquals("CANCELED",store.get(run.id(),1).status());assertNull(store.get(run.id(),1).result());
    }
    static class MemoryStore implements RunStore {
        final Map<String,RunDTO> rows=new ConcurrentHashMap<>();final AtomicInteger failures=new AtomicInteger(),finishCalls=new AtomicInteger();
        public synchronized void insert(String id,RequirementDTO in,String model){rows.put(id,new RunDTO(id,in.userId(),in.id(),in.revision(),"RUNNING",model,"test",in,null,null,null,"now",null));}
        public synchronized void insert(String id,RequirementDTO in,String model,String version){rows.put(id,new RunDTO(id,in.userId(),in.id(),in.revision(),"RUNNING",model,version,in,null,null,null,"now",null));}
        public RunDTO get(String id,long user){RunDTO r=rows.get(id);if(r==null||r.userId()!=user)throw new BusinessException(404,"not found");return r;}
        public List<RunDTO> list(long id,long user){return rows.values().stream().filter(r->r.requirementId()==id&&r.userId()==user).toList();}
        public synchronized boolean finish(String id,String state,AnalysisResult result,String code,String message){
            finishCalls.incrementAndGet();if(failures.getAndUpdate(n->Math.max(0,n-1))>0)throw new DataAccessResourceFailureException("controlled outage");
            RunDTO r=rows.get(id);if(r==null||!r.status().equals("RUNNING"))return false;
            rows.put(id,new RunDTO(id,r.userId(),r.requirementId(),r.requirementRevision(),state,r.model(),r.promptVersion(),r.input(),result,code,message,r.startedAt(),"now"));return true;
        }
        public int recover(){int n=0;for(RunDTO r:List.copyOf(rows.values()))if(finish(r.id(),"FAILED",null,"PROCESS_RESTARTED","restart"))n++;return n;}
    }
}
