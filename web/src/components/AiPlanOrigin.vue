<script setup>
import CandidateRetrievalOrigin from "./CandidateRetrievalOrigin.vue";
defineProps({ origin: Object, published: Boolean });
</script>
<template>
  <section v-if="origin" class="ai-plan-origin">
    <h3>方案来源：AI候选，经商家采用</h3>
    <p v-if="published">
      本页核实结论来自商家。AI原始候选及片段保留供对照，不代表AI已经证明全部条件或偏好得到满足。
    </p>
    <p v-else>原始候选仅供参考，引用支持关系仍需商家逐项核实。</p>
    <CandidateRetrievalOrigin :proposal="origin.candidate" />
    <p v-if="origin.candidate.partialPool">
      生成时的商品范围有截取，不代表已经比较全部商品。
    </p>
    <details>
      <summary>查看原始AI候选及片段（支持关系未核实）</summary>
      <p>运行 {{ origin.runId }} · {{ origin.promptVersion }}</p>
      <article v-for="item in origin.candidate.items" :key="item.productId">
        <h4>{{ item.name }} × {{ item.quantity }}</h4>
        <blockquote v-for="chunk in item.evidence" :key="chunk.chunkId">
          {{ chunk.docTitle }} · {{ chunk.chunkId }}<br />{{ chunk.text }}
        </blockquote>
      </article>
    </details>
  </section>
</template>
<style scoped>
.ai-plan-origin {
  margin: 16px 0;
  padding: 18px;
  background: #f2f6f3;
  border-radius: 12px;
  overflow-wrap: anywhere;
}
p,
blockquote {
  line-height: 1.75;
}
blockquote {
  margin: 12px 0;
  padding-left: 12px;
  border-left: 3px solid #c5d7cd;
  white-space: pre-wrap;
}
h3,
h4 {
  margin: 8px 0;
}
</style>
