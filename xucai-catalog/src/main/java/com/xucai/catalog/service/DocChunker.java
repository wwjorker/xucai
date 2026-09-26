package com.xucai.catalog.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 资料正文的确定性切分规则。纯函数：同一正文永远得到同一组片段与同一组偏移。
 * 只在句末或段落/标题边界切开，**绝不在句子内部切开**，所以"不支持自动双面打印"
 * 不可能被切成"支持自动双面打印"。片段偏移直接指向 product_doc.content，
 * 正文事实源只有一份，片段是派生物。
 */
public final class DocChunker {
    private DocChunker() { }

    /** 规则版本：切分规则一改就要整版重建，旧片段按此字段识别。 */
    public static final String RULE_VERSION = "chunk-v1";
    /** 目标长度：累积到这个长度就在最近的句末收尾。 */
    static final int TARGET = 400;
    /** 超长阈值：单句超过它仍整句保留，只做标记，不截断也不硬切。 */
    static final int MAX = 700;
    /**
     * 标题路径的容量上限，与 product_doc_chunk.heading_path 的 VARCHAR(400) 一致。
     * 标题路径只是查找与显示的辅助信息，权威上下文永远是 locate 给出的原文；
     * 合法的超长标题按"丢最外层、保留最贴近正文的一层"截断并用 … 标出来，
     * 绝不让它把写库撑爆——超列宽会连同资料保存一起回滚。
     */
    static final int MAX_HEADING = 400;
    private static final String TRIMMED = "… > ";
    /** 句末标点。刻意不含英文句点，避免把 1.8米、HL-L2460DW 切碎。 */
    private static final String ENDERS = "。！？!?；;…";
    /** 句末标点后允许一起带走的收尾符号。 */
    private static final String TRAILERS = "”’\")）〕】》」』】";

    public record Chunk(int chunkNo, int start, int end, String headingPath, String text, boolean oversize) {
        public String id(long docId, long version) { return chunkId(docId, version, chunkNo); }
    }

    public static String chunkId(long docId, long version, int chunkNo) {
        return "doc:" + docId + ":v" + version + ":c" + chunkNo;
    }

    /** 解析稳定ID。格式不合法返回 empty，调用方据此拒绝，不猜测。 */
    public static Optional<long[]> parseId(String chunkId) {
        if (chunkId == null) return Optional.empty();
        var m = java.util.regex.Pattern.compile("^doc:([0-9]{1,18}):v([0-9]{1,18}):c([0-9]{1,9})$").matcher(chunkId);
        if (!m.matches()) return Optional.empty();
        long docId = Long.parseLong(m.group(1)), version = Long.parseLong(m.group(2)), no = Long.parseLong(m.group(3));
        if (docId <= 0 || version <= 0 || no <= 0) return Optional.empty();
        return Optional.of(new long[]{docId, version, no});
    }

    public static String hash(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            var text = new StringBuilder(64);
            for (byte b : digest) text.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return text.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 一行的切分结果：标题行只改变上下文，不单独成片段。 */
    private record Line(int start, int end, int headingLevel, String headingText) { }

    private static Line line(String content, int start, int end) {
        int textStart = start, textEnd = end;
        while (textStart < textEnd && Character.isWhitespace(content.charAt(textStart))) textStart++;
        while (textEnd > textStart && Character.isWhitespace(content.charAt(textEnd - 1))) textEnd--;
        int level = 0;
        while (level < 6 && textStart + level < textEnd && content.charAt(textStart + level) == '#') level++;
        if (level > 0 && textStart + level < textEnd && content.charAt(textStart + level) == ' ') {
            int titleStart = textStart + level;
            while (titleStart < textEnd && content.charAt(titleStart) == ' ') titleStart++;
            return new Line(titleStart, textEnd, level, content.substring(titleStart, textEnd));
        }
        return new Line(textStart, textEnd, 0, null);
    }

    /** 把一段连续正文按句末切成句子边界；返回的每个区间都是完整句子。 */
    private static List<int[]> sentences(String content, List<Line> block) {
        List<int[]> result = new ArrayList<>();
        for (Line l : block) {
            int cursor = l.start();
            while (cursor < l.end()) {
                int i = cursor;
                while (i < l.end() && ENDERS.indexOf(content.charAt(i)) < 0) i++;
                if (i < l.end()) {
                    while (i < l.end() && ENDERS.indexOf(content.charAt(i)) >= 0) i++;          // 连续的"？！"一起收
                    while (i < l.end() && TRAILERS.indexOf(content.charAt(i)) >= 0) i++;        // 右引号、右括号跟着句子走
                }
                int start = cursor, end = i;
                while (start < end && Character.isWhitespace(content.charAt(start))) start++;
                while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
                if (end > start) result.add(new int[]{start, end});
                cursor = i;
            }
        }
        return result;
    }

    /**
     * 切分正文。返回片段按 chunkNo 从1递增，偏移一定满足
     * {@code content.substring(start,end).equals(text)}。
     */
    public static List<Chunk> split(String content) {
        Objects.requireNonNull(content, "content");
        List<Chunk> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<Line> block = new ArrayList<>();
        String headingPath = "";
        int cursor = 0, chunkNo = 0;
        // 最后一行没有换行符也要正常处理，收尾在循环外统一 flush。
        while (cursor <= content.length()) {
            int lineEnd = content.indexOf('\n', cursor);
            boolean last = lineEnd < 0;
            Line l = line(content, cursor, last ? content.length() : lineEnd);
            boolean blank = l.end() <= l.start() && l.headingLevel() == 0;
            if (blank || l.headingLevel() > 0) {
                chunkNo = flush(content, block, headingPath, chunkNo, chunks);
                block.clear();
                if (l.headingLevel() > 0) {
                    while (!levels.isEmpty() && levels.get(levels.size() - 1) >= l.headingLevel()) {
                        levels.remove(levels.size() - 1); headings.remove(headings.size() - 1);
                    }
                    levels.add(l.headingLevel()); headings.add(l.headingText());
                    headingPath = headingPath(headings);
                }
            } else {
                block.add(l);
            }
            if (last) break;
            cursor = lineEnd + 1;
        }
        flush(content, block, headingPath, chunkNo, chunks);
        return List.copyOf(chunks);
    }

    private static int flush(String content, List<Line> block, String headingPath, int chunkNo, List<Chunk> out) {
        if (block.isEmpty()) return chunkNo;
        List<int[]> sentences = sentences(content, block);
        int start = -1, end = -1;
        for (int[] s : sentences) {
            if (start < 0) { start = s[0]; end = s[1]; continue; }
            if (s[1] - start > TARGET) {                       // 加上这句就超标：先把已有的收尾
                out.add(chunk(content, ++chunkNo, start, end, headingPath));
                start = s[0]; end = s[1];
            } else { end = s[1]; }
        }
        if (start >= 0) out.add(chunk(content, ++chunkNo, start, end, headingPath));
        return chunkNo;
    }

    /**
     * 拼标题路径并限制在 {@link #MAX_HEADING} 以内。先整层地丢最外层标题，
     * 因为最贴近正文的那一层才是理解片段所需的上下文；丢过就以 "… > " 开头。
     * 只剩最深一层还超长时才截断它本身，并以 … 结尾。结果始终是确定的。
     */
    static String headingPath(List<String> headings) {
        String full = String.join(" > ", headings);
        if (full.length() <= MAX_HEADING) return full;
        for (int from = 1; from < headings.size(); from++) {
            String tail = TRIMMED + String.join(" > ", headings.subList(from, headings.size()));
            if (tail.length() <= MAX_HEADING) return tail;
        }
        String deepest = headings.get(headings.size() - 1);
        return TRIMMED + deepest.substring(0, MAX_HEADING - TRIMMED.length() - 1) + "…";
    }

    private static Chunk chunk(String content, int no, int start, int end, String headingPath) {
        String text = content.substring(start, end);
        return new Chunk(no, start, end, headingPath, text, text.length() > MAX);
    }
}
