package com.xucai.aigc.procurement;

import com.xucai.common.annotations.NoWrapper;
import com.xucai.common.utils.MallIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.*;

@RestController @Validated @RequiredArgsConstructor @RequestMapping("/runs")
public class AnalysisRunController {
    private final AnalysisRunService service;
    public record StartForm(@NotNull @Positive Long requirementId,@NotNull @Positive Long expectedRevision,
                            @Pattern(regexp="REQUIREMENT|CANDIDATE") String kind) { }
    @GetMapping("/capability") public Map<String,Object> capability(){MallIdentity.buyer();boolean available=service.available();return Map.of("available",available,"message",available?"可使用AI整理已保存的需求；结果需人工核对，不自动修改需求":"AI整理当前不可用（未启用或服务暂不可用），可以先人工编辑需求");}
    @PostMapping public RunDTO start(@RequestBody @Valid StartForm form){return service.start(form.requirementId(),MallIdentity.buyer(),form.expectedRevision(),"CANDIDATE".equals(form.kind()));}
    @GetMapping public List<RunDTO> list(@RequestParam @Positive long requirementId){return service.list(requirementId,MallIdentity.buyer());}
    @GetMapping("/{id}") public RunDTO get(@PathVariable String id){return service.get(id,MallIdentity.buyer());}
    @PostMapping("/{id}/cancel") public RunDTO cancel(@PathVariable String id){return service.cancel(id,MallIdentity.buyer());}
    @NoWrapper @GetMapping(value="/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RunDTO>> events(@PathVariable String id){
        long user=MallIdentity.buyer();service.get(id,user); // 写SSE响应前检查归属，保留真实401/404。
        // 只读订阅：断开不修改run；每次订阅都读持久化快照，终态订阅也能取得结果。
        return Flux.interval(Duration.ZERO,Duration.ofSeconds(1)).onBackpressureLatest()
                .publishOn(Schedulers.boundedElastic(),1).map(t->service.get(id,user))
                .takeUntil(run->!"RUNNING".equals(run.status())).take(120)
                .map(run->ServerSentEvent.builder(run).event("state").id(run.id()+":"+run.status()).build());
    }
}
