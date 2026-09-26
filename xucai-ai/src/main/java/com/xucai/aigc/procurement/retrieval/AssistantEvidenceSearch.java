package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.aigc.procurement.ModelCallException;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;

/** Buyer-scoped retrieval of current published evidence. Never builds an index during chat. */
@Component
@Profile("xucai")
public class AssistantEvidenceSearch {
    private final CandidateCatalogClient catalog;
    private final ObjectMapper mapper;
    private final RetrievalStore store;
    private final VectorIndex vectors;
    private final EmbeddingClient embeddings;

    @Autowired
    public AssistantEvidenceSearch(
            CandidateCatalogClient catalog,
            ObjectMapper mapper,
            ObjectProvider<RetrievalStore> store,
            ObjectProvider<VectorIndex> vectors,
            ObjectProvider<EmbeddingClient> embeddings) {
        this(
                catalog,
                mapper,
                store.getIfAvailable(),
                vectors.getIfAvailable(),
                embeddings.getIfAvailable());
    }

    AssistantEvidenceSearch(
            CandidateCatalogClient catalog,
            ObjectMapper mapper,
            RetrievalStore store,
            VectorIndex vectors,
            EmbeddingClient embeddings) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.store = store;
        this.vectors = vectors;
        this.embeddings = embeddings;
    }

    public record Result(
            boolean ready,
            boolean semantic,
            String mode,
            String fallbackReason,
            List<Chunk> chunks,
            int total,
            boolean complete,
            String notice,
            @com.fasterxml.jackson.annotation.JsonIgnore Map<String, Object> ranking) {}

    private String hash(IndexSnapshot snapshot) {
        return RetrievalRules.hash(RetrievalRules.json(snapshot, mapper));
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted())
            throw new ModelCallException("MODEL_CANCELED", "已停止资料检索");
    }

    public Result search(String callId, long product, long user, String query) {
        if (query == null || query.isBlank() || query.length() > 300)
            throw new ModelCallException("RETRIEVAL_QUERY", "请简要说明想了解的设备参数");
        active();
        var snapshot = catalog.indexSnapshot(product, user, 2L);
        RetrievalRules.snapshot(snapshot, product);
        String fingerprint = hash(snapshot), fallback = "NOT_BUILT";
        List<Chunk> selected = null;
        Map<String, Object> ranking = Map.of();
        if (store != null && vectors != null && embeddings != null) {
            try {
                var index = store.index(product);
                if (index.isPresent() && index.get().hash().equals(fingerprint)) {
                    vectors.ensureSchema();
                    if (vectors.complete(
                            index.get().generation(),
                            snapshot.chunks().stream().map(Chunk::chunkId).toList())) {
                        active();
                        var embedded = embeddings.embedAssistant(callId, query);
                        active();
                        if (embedded.size() != 1)
                            throw new ModelCallException("RETRIEVAL_VECTOR", "查询向量不完整");
                        Map<String, Chunk> sources = new HashMap<>();
                        for (var chunk : snapshot.chunks())
                            sources.put(
                                    vectors.key(index.get().generation(), chunk.chunkId()), chunk);
                        var hits =
                                vectors.search(
                                        List.of(index.get().generation()), embedded.get(0), 4);
                        if (hits.size() != Math.min(4, snapshot.chunks().size()))
                            throw new ModelCallException("RETRIEVAL_HITS", "检索结果不完整");
                        var ranked = new ArrayList<Chunk>();
                        Set<String> seen = new HashSet<>();
                        for (var hit : hits) {
                            if (!sources.containsKey(hit.key())
                                    || !seen.add(hit.key())
                                    || !Double.isFinite(hit.distance())
                                    || hit.distance() < 0
                                    || hit.distance() > 2)
                                throw new ModelCallException("RETRIEVAL_HITS", "检索范围无效");
                            ranked.add(sources.get(hit.key()));
                        }
                        var keywords = lexical(snapshot.chunks(), query);
                        selected = fuse(ranked, keywords, 4);
                        ranking =
                                Map.of(
                                        "queryHash",
                                        RetrievalRules.hash(query),
                                        "vector",
                                        ranked.stream().map(Chunk::chunkId).toList(),
                                        "keyword",
                                        keywords.stream().map(Chunk::chunkId).toList(),
                                        "fused",
                                        selected.stream().map(Chunk::chunkId).toList(),
                                        "rrfK",
                                        60);
                    } else fallback = "INCOMPLETE";
                } else if (index.isPresent()) fallback = "STALE_INDEX";
            } catch (RuntimeException e) {
                active();
                fallback = e instanceof ModelCallException m ? m.code() : "VECTOR_UNAVAILABLE";
            }
        }
        boolean semantic = selected != null;
        if (selected == null) selected = lexical(snapshot.chunks(), query);
        Map<Long, DocumentManifest> docs = new HashMap<>();
        snapshot.documents().forEach(d -> docs.put(d.docId(), d));
        for (var chunk : selected) {
            active();
            if (!RetrievalRules.located(
                    chunk,
                    docs.get(chunk.docId()),
                    catalog.locate(product, chunk.chunkId(), user, 2L)))
                throw new ModelCallException("RETRIEVAL_STALE", "商品资料已更新，请重新查询");
        }
        active();
        var after = catalog.indexSnapshot(product, user, 2L);
        RetrievalRules.snapshot(after, product);
        if (!fingerprint.equals(hash(after)))
            throw new ModelCallException("RETRIEVAL_STALE", "检索期间资料已变化，请重新查询");
        return new Result(
                true,
                semantic,
                semantic ? "HYBRID" : "KEYWORD",
                semantic ? "" : fallback,
                List.copyOf(selected),
                snapshot.chunks().size(),
                selected.size() == snapshot.chunks().size(),
                semantic
                        ? "以下为向量与关键词融合检索的当前资料片段，相关不等于支持结论；按原文回答，保留否定和适用范围。"
                        : "本次按关键词查找当前资料，未使用语义检索。没有匹配片段不代表设备不支持；请明确说明资料不足，不自行补充。 ",
                ranking);
    }

    /** Reciprocal rank fusion: each independent ranked list contributes 1/(60+rank). */
    static List<Chunk> fuse(List<Chunk> vector, List<Chunk> keyword, int limit) {
        Map<String, Chunk> chunks = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (var ranking : List.of(vector, keyword)) {
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (var chunk : ranking) {
                if (!seen.add(chunk.chunkId())) continue;
                rank++;
                chunks.putIfAbsent(chunk.chunkId(), chunk);
                scores.merge(chunk.chunkId(), 1.0 / (60 + rank), Double::sum);
            }
        }
        // LinkedHashMap and stable sorting keep deterministic order when scores tie.
        return chunks.values().stream()
                .sorted(Comparator.comparingDouble((Chunk c) -> scores.get(c.chunkId())).reversed())
                .limit(limit)
                .toList();
    }

    static List<Chunk> lexical(List<Chunk> chunks, String query) {
        Set<String> terms = new LinkedHashSet<>();
        String q = query.toLowerCase(Locale.ROOT);
        var words = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9.-]*|[\\p{IsHan}]+").matcher(q);
        while (words.find()) {
            String word = words.group();
            terms.add(word);
            if (word.matches("[\\p{IsHan}]+"))
                for (int i = 0; i + 1 < word.length(); i++) terms.add(word.substring(i, i + 2));
        }
        Map<Chunk, Long> scores = new HashMap<>();
        for (var c : chunks) {
            String text = c.text().toLowerCase(Locale.ROOT);
            scores.put(c, terms.stream().filter(text::contains).count());
        }
        return chunks.stream()
                .filter(c -> scores.get(c) > 0)
                .sorted(Comparator.comparingLong((Chunk c) -> scores.get(c)).reversed())
                .limit(8)
                .toList();
    }
}
