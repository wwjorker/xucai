package com.xucai.aigc.procurement;

import com.xucai.api.client.trade.RequirementClient;
import com.xucai.api.dto.mall.RequirementDTO;
import com.xucai.common.exceptions.BusinessException;
import feign.FeignException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AnalysisRunService {
    private final RunStore store;
    private final RequirementClient requirements;
    private final RequirementAnalyzer analyzer;
    private final Validator validator;
    private final ExecutorService workers;
    private final ScheduledExecutorService timers;
    private final long timeoutMillis;
    private long candidateTimeoutMillis;
    private final Map<String,FutureTask<Void>> active=new ConcurrentHashMap<>();
    private final Map<String,ScheduledFuture<?>> timeouts=new ConcurrentHashMap<>();
    private record Outcome(String status, AnalysisResult result, String code, String message) { }
    private final Map<String,Outcome> pending=new ConcurrentHashMap<>();
    // 包含等待数据库收尾的任务，故障期间也不无限积累内存。
    private final Semaphore capacity=new Semaphore(10);
    private volatile boolean ready;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisRunService(RunStore store,RequirementClient requirements,RequirementAnalyzer analyzer,Validator validator){
        this(store,requirements,analyzer,validator,new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),namedThreads("procurement-analysis-"),new ThreadPoolExecutor.AbortPolicy()),
                Executors.newSingleThreadScheduledExecutor(namedThreads("procurement-deadline-")),60000);
        this.candidateTimeoutMillis=120000;
    }
    // 显式执行器参数仅供受控测试；正常环境不会装配测试分析器。
    AnalysisRunService(RunStore store,RequirementClient requirements,RequirementAnalyzer analyzer,Validator validator,
                       ExecutorService workers,ScheduledExecutorService timers,long timeoutMillis){
        this.store=store;this.requirements=requirements;this.analyzer=analyzer;this.validator=validator;
        this.workers=workers;this.timers=timers;this.timeoutMillis=timeoutMillis;this.candidateTimeoutMillis=timeoutMillis;
    }
    private static ThreadFactory namedThreads(String prefix){var count=new java.util.concurrent.atomic.AtomicInteger();return r->{Thread t=new Thread(r,prefix+count.incrementAndGet());t.setDaemon(true);return t;};}
    @PostConstruct public void initialize(){
        store.recover();
        timers.scheduleWithFixedDelay(()->pending.forEach((id,outcome)->persist(id,outcome)),1,1,TimeUnit.SECONDS);
        ready=true;
    }
    public boolean available(){return ready&&analyzer.available();}
    public RequirementDTO requirement(long id,long user){
        try{return requirements.get(id,user,2L);}catch(FeignException e){if(e.status()==404)throw new BusinessException(404,"需求不存在或不属于当前账户");throw new BusinessException(503,"需求服务暂不可用，请稍后再试");}
    }
    public RunDTO start(long requirementId,long user,long revision){
        return start(requirementId,user,revision,false);
    }
    public RunDTO start(long requirementId,long user,long revision,boolean candidate){
        if(!ready)throw new BusinessException(503,"需求整理服务正在初始化");
        RequirementDTO input=requirement(requirementId,user);
        if(!input.userId().equals(user))throw new BusinessException(404,"需求不存在");
        if(!input.revision().equals(revision)||!"OPEN".equals(input.status()))throw new BusinessException(409,"需求版本已变化或已关闭，请先读取最新需求");
        if(candidate)try{CandidatePlanner.quantities(input);}catch(ModelCallException e){throw new BusinessException(400,e.getMessage());}
        if(!capacity.tryAcquire())throw new BusinessException(503,"当前整理任务较多，请稍后重试");
        String id=UUID.randomUUID().toString();
        try{if(candidate)store.insert(id,input,analyzer.model(),CandidatePlanner.VERSION);else store.insert(id,input,analyzer.model());}catch(RuntimeException e){capacity.release();throw e;} // 已提交后才允许异步执行。
        FutureTask<Void> task=new FutureTask<>(()->{execute(id,input,candidate);return null;});
        active.put(id,task);
        try{
            timeouts.put(id,timers.schedule(()->{
                if(!active.containsKey(id))return;
                var outcome=pending.computeIfAbsent(id,key->new Outcome("FAILED",null,"TIMEOUT","整理超时，请稍后重试"));
                // 先中断收费流水线，再尝试写终态。DB故障不能让已超时任务继续发下一阶段请求。
                var running=active.get(id);if(running!=null)running.cancel(true);
                persist(id,outcome);
            },candidate?candidateTimeoutMillis:timeoutMillis,TimeUnit.MILLISECONDS));
            workers.execute(task);
        }catch(RejectedExecutionException e){complete(id,"FAILED",null,"BUSY","当前整理任务较多，请稍后重试");}
        return store.get(id,user);
    }
    private void execute(String id,RequirementDTO input,boolean candidate){
        try{
            if(!"RUNNING".equals(store.get(id,input.userId()).status())){stopTask(id);return;}
            AnalysisResult result=candidate?analyzer.candidates(input,id):analyzer.analyze(input,id);
            if(result==null||!validator.validate(result).isEmpty())throw new IllegalArgumentException("invalid analysis output");
            RequirementDTO current=requirement(input.id(),input.userId());
            if(!current.revision().equals(input.revision())||!"OPEN".equals(current.status())){
                complete(id,"FAILED",null,"STALE_REQUIREMENT","需求已修改或关闭，请使用最新版本重新整理");return;
            }
            // 仅保存本run的历史结果，不自动跨库覆盖需求；应用结果仍需交易端revision条件更新。
            complete(id,"DONE",result,null,null);
        }catch(DisabledRequirementAnalyzer.ModelUnavailableException e){complete(id,"FAILED",null,"MODEL_DISABLED","AI整理暂未启用，已保存的采购需求仍可继续编辑");}
        catch(ModelCallException e){complete(id,"FAILED",null,e.code(),e.getMessage());}
        catch(BusinessException e){complete(id,"FAILED",null,"REQUIREMENT_UNAVAILABLE","暂时无法核对需求，请稍后重试");}
        catch(Exception e){complete(id,"FAILED",null,"ANALYSIS_FAILED","需求整理未完成，请稍后重试");}
    }
    private void complete(String id,String status,AnalysisResult result,String code,String message){
        if(!active.containsKey(id))return;
        Outcome outcome=pending.computeIfAbsent(id,key->new Outcome(status,result,code,message));
        persist(id,outcome);
    }
    private void persist(String id,Outcome outcome){
        try{
            store.finish(id,outcome.status(),outcome.result(),outcome.code(),outcome.message());
            // CAS失败也表示已有终态；数据库明确响应后才释放任务和容量。
            pending.remove(id,outcome);stopTask(id);
        }catch(org.springframework.dao.DataAccessException e){
            // 一秒一次、最多十个待收尾任务。数据库恢复后重试；进程重启由recover兜底。
        }
    }
    public RunDTO get(String id,long user){return store.get(id,user);}
    public List<RunDTO> list(long requirementId,long user){requirement(requirementId,user);return store.list(requirementId,user);}
    public RunDTO cancel(String id,long user){
        store.get(id,user);
        if(store.finish(id,"CANCELED",null,"USER_CANCELED","已取消需求整理"))stopTask(id);
        return store.get(id,user);
    }
    private void stopTask(String id){
        var task=active.remove(id);
        if(task!=null){task.cancel(true);if(workers instanceof ThreadPoolExecutor pool)pool.remove(task);capacity.release();}
        pending.remove(id);var timer=timeouts.remove(id);if(timer!=null)timer.cancel(false);
    }
    @PreDestroy public void close(){
        ready=false;
        try{for(String id:List.copyOf(active.keySet())){
            try{store.finish(id,"FAILED",null,"SERVICE_STOPPED","服务已停止，请稍后重试");}
            catch(org.springframework.dao.DataAccessException ignored){/* 启动时收尾全部遗留RUNNING。 */}
            finally{stopTask(id);}
        }}finally{workers.shutdownNow();timers.shutdownNow();}
    }
}
