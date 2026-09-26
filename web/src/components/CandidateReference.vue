<script setup>
import { money, safeUrl } from "../domain";
import CandidateRetrievalOrigin from "./CandidateRetrievalOrigin.vue";
defineProps({ proposal: Object });
</script>
<template>
  <section class="candidate-reference">
    <h3>商品候选参考 · 待商家核实</h3>
    <p>{{ proposal.notice }}</p>
    <CandidateRetrievalOrigin :proposal="proposal" />
    <p>
      本次参考 {{ proposal.consideredProducts }} 款商品<span
        v-if="proposal.partialPool"
        >，候选或资料范围有截取</span
      >；不代表全目录最优结果。
    </p>
    <p v-if="proposal.status === 'NO_SELECTION'">
      模型未从本次范围选出组合，不代表所有商品都无法满足需求。
    </p>
    <template v-else>
      <p>
        <strong>读取时合计 ¥ {{ money(proposal.totalAmount) }}</strong>
      </p>
      <article v-for="item in proposal.items" :key="item.productId">
        <h4>{{ item.name }} × {{ item.quantity }}</h4>
        <p>
          单价 ¥ {{ money(item.unitPrice) }} · 规格第 {{ item.specVersion }} 版
        </p>
        <RouterLink :to="'/products/' + item.productId"
          >查看当前商品 →</RouterLink
        >
        <p v-if="!item.evidence.length">没有选取资料依据，满足情况待核实。</p>
        <div v-for="e in item.evidence" :key="e.chunkId">
          <p>{{ e.docTitle }} · 第 {{ e.version }} 版 · 支持关系待核实</p>
          <blockquote>{{ e.text }}</blockquote>
          <small
            >保存时出处：{{ e.chunkId }} · 原文 {{ e.charStart }}–{{
              e.charEnd
            }}
            字</small
          >
          <a
            v-if="safeUrl(e.sourceUrl)"
            :href="safeUrl(e.sourceUrl)"
            target="_blank"
            rel="noopener noreferrer"
            >来源网站 ↗</a
          >
        </div>
      </article>
    </template>
    <p>
      这是历史参考快照。价格、库存及资料可能变化，不预占库存，也不能直接下单。
    </p>
  </section>
</template>
<style scoped>
.candidate-reference {
  overflow-wrap: anywhere;
}
article {
  margin: 16px 0;
  padding-top: 12px;
  border-top: 1px solid #dce4dc;
}
blockquote {
  white-space: pre-wrap;
  margin: 12px 0;
  padding: 10px;
  background: #fff;
  border-left: 3px solid #789486;
}
small,
a {
  display: block;
  margin-top: 8px;
}
</style>
