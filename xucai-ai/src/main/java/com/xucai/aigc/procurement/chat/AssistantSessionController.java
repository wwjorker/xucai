package com.xucai.aigc.procurement.chat;

import com.xucai.common.utils.MallIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Profile("xucai") @RequestMapping("/assistant/sessions")
public class AssistantSessionController {
    private final AssistantSessionStore store;
    private final AssistantOrchestrator assistant;
    public AssistantSessionController(AssistantSessionStore store,AssistantOrchestrator assistant){this.store=store;this.assistant=assistant;}
    public record Create(@NotBlank String id,@NotBlank @Size(max=80) String title) {}
    public record Edit(@NotNull @PositiveOrZero Long expectedRevision,@Size(min=1,max=80) String title,Boolean archived) {}
    @GetMapping public List<AssistantSessionStore.Session> list(){return store.list(MallIdentity.buyer());}
    @PostMapping public AssistantSessionStore.Session create(@RequestBody @Valid Create form){return store.create(MallIdentity.buyer(),form.id(),form.title());}
    @GetMapping("/{id}") public AssistantSessionStore.Session get(@PathVariable String id){return store.get(MallIdentity.buyer(),id);}
    @PatchMapping("/{id}") public AssistantSessionStore.Session edit(@PathVariable String id,@RequestBody @Valid Edit form){return store.edit(MallIdentity.buyer(),id,form.expectedRevision(),form.title(),form.archived());}
    @GetMapping("/{id}/turns") public List<AssistantSessionStore.Turn> turns(@PathVariable String id){return store.turns(MallIdentity.buyer(),id);}
    @GetMapping("/{id}/turns/{turn}") public AssistantSessionStore.Turn turn(@PathVariable String id,@PathVariable String turn){return store.turn(MallIdentity.buyer(),id,turn);}
    @com.xucai.common.annotations.NoWrapper
    @GetMapping(value="/{id}/turns/{turn}/events",produces=org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<AssistantProgress.Frame>> events(@PathVariable String id,@PathVariable String turn){
        var events=assistant.events(MallIdentity.buyer(),id,turn);
        return events.map(frame->org.springframework.http.codec.ServerSentEvent.builder(frame).event("message").build());
    }
    public record Send(@NotBlank String id,@NotBlank @Size(max=4000) String text) {}
    @GetMapping("/capability") public java.util.Map<String,Object> capability(){MallIdentity.buyer();return java.util.Map.of("available",assistant.available(),"maxRequestsPerTurn",5,"pilotQuotaApplies",false);}
    @PostMapping("/{id}/turns") public AssistantSessionStore.Turn send(@PathVariable String id,@RequestBody @Valid Send form){return assistant.send(MallIdentity.buyer(),id,form.id(),form.text());}
    @PostMapping("/{id}/turns/{turn}/cancel") public AssistantSessionStore.Turn cancel(@PathVariable String id,@PathVariable String turn){return assistant.cancel(MallIdentity.buyer(),id,turn);}
}
