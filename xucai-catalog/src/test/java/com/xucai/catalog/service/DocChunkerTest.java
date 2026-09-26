package com.xucai.catalog.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 切分规则的风险点：原文定位、否定与限制不被切碎、确定性、稳定ID、标题路径不超列宽。 */
class DocChunkerTest {
    private static final String DOC = """
            # Brother HL-L2460DW

            ## 自动双面打印
            不支持自动双面打印；需要手动翻面。

            ## 供电
            本页标注为110—120V机型。直接接入220V电网可能损坏设备；采购前须核实地区版本。
            """;

    /** 用 String.join 拼多行，省得在测试里到处写转义。 */
    private static String lines(String... parts) {
        return String.join("\n", parts) + "\n";
    }

    @Test
    void offsetsAlwaysPointAtTheRealText() {
        for (DocChunker.Chunk c : DocChunker.split(DOC))
            assertEquals(c.text(), DOC.substring(c.start(), c.end()), "片段编号" + c.chunkNo());
    }

    @Test
    void negationStaysInsideOneChunk() {
        var chunks = DocChunker.split(DOC);
        assertTrue(chunks.stream().anyMatch(c -> c.text().contains("不支持自动双面打印")));
        // 不能出现只剩“支持自动双面打印”的片段。
        assertTrue(chunks.stream().noneMatch(c -> c.text().startsWith("支持自动双面打印")));
        assertTrue(chunks.stream().anyMatch(c -> c.text().contains("110—120V") && c.text().contains("220V")));
    }

    @Test
    void headingPathKeepsContextForShortAnswers() {
        var chunks = DocChunker.split(DOC);
        var duplex = chunks.stream().filter(c -> c.text().contains("不支持")).findFirst().orElseThrow();
        assertEquals("Brother HL-L2460DW > 自动双面打印", duplex.headingPath());
    }

    @Test
    void sameContentAlwaysProducesSameChunks() {
        var first = DocChunker.split(DOC);
        var second = DocChunker.split(DOC);
        assertEquals(first, second);
        assertEquals(DocChunker.hash(DOC), DocChunker.hash(DOC));
        assertNotEquals(DocChunker.hash(DOC), DocChunker.hash(DOC + "补充说明。"));
    }

    @Test
    void chunkNumbersAreContinuousAndRangesDoNotOverlap() {
        var chunks = DocChunker.split(DOC);
        assertFalse(chunks.isEmpty());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).chunkNo());
            assertTrue(chunks.get(i).end() > chunks.get(i).start());
            if (i > 0) assertTrue(chunks.get(i).start() >= chunks.get(i - 1).end());
        }
    }

    @Test
    void aSingleLongSentenceIsNeverCutInHalf() {
        String sentence = "本机型" + "在长时间连续打印时纸张温度会升高".repeat(60) + "，但不支持无人值守使用。";
        var chunks = DocChunker.split(sentence);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().length() > DocChunker.MAX);
        assertTrue(chunks.get(0).oversize());
        assertTrue(chunks.get(0).text().endsWith("不支持无人值守使用。"));
    }

    @Test
    void longBlocksSplitAtSentenceBoundariesOnly() {
        String block = "第一句话用于说明接口数量。".repeat(60);
        var chunks = DocChunker.split(block);
        assertTrue(chunks.size() > 1);
        for (DocChunker.Chunk c : chunks) {
            assertTrue(c.text().endsWith("。"), c.text());
            assertTrue(c.text().startsWith("第一句"), c.text());
            assertFalse(c.oversize());
        }
        assertEquals(block, String.join("", chunks.stream().map(DocChunker.Chunk::text).toList()));
    }

    @Test
    void decimalsAndModelNumbersAreNotTreatedAsSentenceEnds() {
        var chunks = DocChunker.split("线长1.8米，型号HL-L2460DW.适用于办公。");
        assertEquals(1, chunks.size());
        assertEquals("线长1.8米，型号HL-L2460DW.适用于办公。", chunks.get(0).text());
    }

    @Test
    void blankAndWhitespaceOnlyContentProducesNoChunk() {
        assertEquals(List.of(), DocChunker.split(""));
        assertEquals(List.of(), DocChunker.split(lines("   ", " \t ")));
        assertEquals(List.of(), DocChunker.split(lines("# 只有标题")));
    }

    @Test
    void contentWithoutTrailingNewlineKeepsItsLastLine() {
        var chunks = DocChunker.split("## 接口\nUSB-C下行数据口不传视频");
        assertEquals(1, chunks.size());
        assertEquals("USB-C下行数据口不传视频", chunks.get(0).text());
        assertEquals("接口", chunks.get(0).headingPath());
    }

    @Test
    void stableIdRoundTripsAndRejectsBadInput() {
        assertEquals("doc:12:v3:c4", DocChunker.chunkId(12, 3, 4));
        assertArrayEquals(new long[]{12, 3, 4}, DocChunker.parseId("doc:12:v3:c4").orElseThrow());
        for (String bad : List.of("doc:12:v3:c0", "doc:0:v3:c1", "doc:12:c4", "doc:12:v3:c4 ", "", "doc:a:v3:c4"))
            assertTrue(DocChunker.parseId(bad).isEmpty(), bad);
        assertTrue(DocChunker.parseId(null).isEmpty());
    }

    @Test
    void longNestedHeadingPathIsCappedAtColumnWidthAndKeepsTheDeepestLevel() {
        String outer = "外层标题".repeat(60);           // 240 字
        String middle = "中间层标题".repeat(40);         // 200 字
        String leaf = "最贴近正文的那一层";
        String doc = lines("# " + outer, "## " + middle, "### " + leaf, "正文一句话。");
        var chunks = DocChunker.split(doc);
        assertEquals(1, chunks.size());
        String path = chunks.get(0).headingPath();
        assertTrue(path.length() <= DocChunker.MAX_HEADING, "实际长度" + path.length());
        assertTrue(path.endsWith(leaf), path);           // 最深一层必须完整保留
        assertTrue(path.startsWith("… > "), path);       // 丢过外层就要标出来
        assertFalse(path.contains(outer), "最外层应被整层丢掉");
        assertEquals(path, DocChunker.split(doc).get(0).headingPath());
        assertEquals("正文一句话。", chunks.get(0).text());
        assertEquals(doc.substring(chunks.get(0).start(), chunks.get(0).end()), chunks.get(0).text());
    }

    @Test
    void aSingleHeadingLongerThanTheColumnIsTruncatedNotDropped() {
        String huge = "超长标题".repeat(200);            // 800 字，单独一层就超列宽
        var chunks = DocChunker.split(lines("# " + huge, "正文一句话。"));
        String path = chunks.get(0).headingPath();
        assertEquals(DocChunker.MAX_HEADING, path.length());
        assertTrue(path.startsWith("… > ") && path.endsWith("…"), path);
        assertTrue(huge.startsWith(path.substring(4, path.length() - 1)), "截断内容必须来自原标题");
    }

    @Test
    void headingPathHelperIsDeterministicAndNeverExceedsTheLimit() {
        for (int levels = 1; levels <= 8; levels++) {
            var headings = new java.util.ArrayList<String>();
            for (int i = 0; i < levels; i++) headings.add("层级" + i + "标题".repeat(30));
            String path = DocChunker.headingPath(headings);
            assertTrue(path.length() <= DocChunker.MAX_HEADING, levels + "层实际长度" + path.length());
            assertEquals(path, DocChunker.headingPath(headings));
        }
        assertEquals("甲 > 乙", DocChunker.headingPath(List.of("甲", "乙")));
    }

    @Test
    void keywordTermsAreDeduplicatedCappedAndMatchesLocated() {
        assertEquals(List.of("双面", "220V"), DocChunkService.terms("  双面   220V  双面 "));
        assertEquals(5, DocChunkService.terms("a b c d e f g").size());
        assertEquals(List.of(), DocChunkService.terms("   "));
        var found = DocChunkService.matches("不支持双面；双面需手动", List.of("双面"));
        assertEquals(2, found.size());
        assertEquals(3, found.get(0).get("start"));
        assertEquals(6, found.get(1).get("start"));
    }
}
