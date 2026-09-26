package com.xucai.aigc.procurement.chat;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** One bounded snapshot per running turn. Durable history remains in AssistantSessionStore. */
final class AssistantProgress {
    record Frame(String status,String text,AssistantSessionStore.Turn turn) { }
    private final Sinks.Many<Frame> events=Sinks.many().replay().latest();
    private boolean finished;
    AssistantProgress(){events.tryEmitNext(new Frame("RUNNING","",null));}
    synchronized void text(String text){if(!finished)events.tryEmitNext(new Frame("RUNNING",text,null));}
    synchronized void finish(AssistantSessionStore.Turn turn){if(finished)return;finished=true;events.tryEmitNext(new Frame(turn.status(),"",turn));events.tryEmitComplete();}
    synchronized void fail(){if(finished)return;finished=true;events.tryEmitError(new IllegalStateException("读取最终回复失败，请刷新记录"));}
    Flux<Frame> flux(){return events.asFlux();}
}
