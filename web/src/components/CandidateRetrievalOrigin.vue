<script setup>
defineProps({ proposal: Object });
</script>
<template>
  <section v-if="proposal?.semantic && proposal.retrieval" class="retrieval-origin">
    <p>
      已检索商品资料后生成 · 每款最多参考
      {{ proposal.retrieval.perProductTopK }} 个相似片段。相似不代表满足采购条件。
    </p>
    <details>
      <summary>查看生成时的资料检索范围</summary>
      <p>以下为历史检索记录，资料是否仍有效须以当前核对为准。</p>
      <p>向量模型 {{ proposal.retrieval.model }} · {{ proposal.retrieval.dimensions }} 维</p>
      <ul>
        <li v-for="scope in proposal.retrieval.products" :key="scope.productId">
          商品 {{ scope.productId }}：
          <span v-if="scope.suppliedChunkIds.length">送入生成模型 {{ scope.suppliedChunkIds.join('、') }}</span>
          <span v-else>因输入范围限制，未送入资料片段</span>
        </li>
      </ul>
    </details>
  </section>
</template>
<style scoped>
.retrieval-origin { padding: 12px; background: #eef4f0; border-radius: 8px; overflow-wrap: anywhere; }
p, li { line-height: 1.7; }
ul { padding-left: 20px; }
</style>
