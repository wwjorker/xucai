package com.xucai.aigc.procurement.chat;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AssistantProgressTest {
    @Test void cancellationIsTerminalAndLateTextCannotOverwriteIt(){
        var progress=new AssistantProgress();List<AssistantProgress.Frame> seen=new ArrayList<>();progress.flux().subscribe(seen::add);
        progress.text("生成中");var canceled=new AssistantSessionStore.Turn("t","s",1,"问题","CANCELED",null,null,null,"now","now");
        progress.finish(canceled);progress.text("不能再出现");progress.finish(canceled);
        assertEquals(3,seen.size());assertEquals("CANCELED",seen.get(2).status());
        assertEquals("CANCELED",progress.flux().blockLast().status());
    }
}
