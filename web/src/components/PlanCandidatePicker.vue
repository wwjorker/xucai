<script setup>
import { computed, ref } from "vue";
import { categories, categoryOf, money } from "../domain";
import {
  categoryProgress,
  draftBudget,
  filterCandidates,
  suggestedQuantity,
  knownPrice,
} from "../plan-candidates.js";
const props = defineProps({
  products: Array,
  details: Object,
  lines: Array,
  disabled: Boolean,
});
defineEmits(["add"]);
const category = ref("required"),
  keyword = ref("");
const progress = computed(() => categoryProgress(props.details, props.lines));
const budget = computed(() => draftBudget(props.details, props.lines));
const candidates = computed(() =>
  filterCandidates(props.products, props.details, {
    category: category.value,
    keyword: keyword.value,
  }),
);
const quantityFor = (product) =>
  suggestedQuantity(props.details, props.lines, product.categoryId);
</script>
<template>
  <section class="candidate-panel" aria-label="按需求挑选商品">
    <h3>按需求挑选商品</h3>
    <p class="muted">
      按已保存的分类和商品名称筛选。价格、库存仅为读取时参考；用途和必须条件请查资料后核实。
    </p>
    <ul v-if="progress.length" class="quantity-progress">
      <li v-for="row in progress" :key="row.categoryId">
        {{ categoryOf(row.categoryId).name }}：需求
        {{ row.quantity ?? "待确认" }} 件 · 已选 {{ row.chosen ?? "待补齐" }} 件
        <span v-if="row.remaining != null"> · 还需 {{ row.remaining }} 件</span>
        <strong v-if="row.quantity != null && row.chosen > row.quantity">
          · 已超出需求</strong
        >
      </li>
    </ul>
    <p v-else>尚未确定设备分类，当前展示全部在售商品。</p>
    <p class="budget-overview">
      已选估算：{{
        budget.amount == null ? "请补齐件数" : "¥ " + money(budget.amount)
      }}
      <template v-if="budget.remaining != null">
        · 整单{{ budget.remaining < 0 ? "超出预算" : "剩余预算" }} ¥
        {{ money(Math.abs(budget.remaining)) }}</template
      >
      <template v-else-if="details.budgetCents == null">
        · 总预算待确认</template
      >
    </p>
    <p class="muted">
      整单预算由所有设备共用；部分商品在预算内不代表整套方案满足预算。
    </p>
    <div class="candidate-filters">
      <el-select
        v-model="category"
        aria-label="备选商品分类"
        :disabled="disabled"
      >
        <el-option label="需求中的分类" value="required" />
        <el-option label="全部分类" value="all" />
        <el-option
          v-for="c in categories"
          :key="c.id"
          :label="c.name"
          :value="String(c.id)"
        />
      </el-select>
      <el-input
        v-model="keyword"
        aria-label="备选商品名称"
        placeholder="按商品名称查找"
        clearable
        :disabled="disabled"
      />
    </div>
    <p class="muted">找到 {{ candidates.length }} 款在售商品</p>
    <div class="candidate-list">
      <article v-for="p in candidates" :key="p.id" class="candidate-card">
        <strong>{{ p.name }}</strong>
        <p>
          {{ categoryOf(p.categoryId).name }} · 单价 ¥
          {{ knownPrice(p) ? money(p.sale.price) : "暂不可用" }} · 库存
          {{ p.sale.stock ?? "未知" }}
        </p>
        <p v-if="quantityFor(p)">
          按该类剩余 {{ quantityFor(p) }} 件估算 ¥
          {{
            knownPrice(p)
              ? money(Number(quantityFor(p)) * Number(p.sale.price))
              : "暂不可用"
          }}
          <span
            v-if="
              p.sale.stock != null &&
              Number(p.sale.stock) < Number(quantityFor(p))
            "
          >
            · 库存不足，请调整组合</span
          >
        </p>
        <p v-else>加入后请填写件数</p>
        <el-button
          :disabled="
            disabled ||
            !knownPrice(p) ||
            lines.some((line) => String(line.product.id) === String(p.id))
          "
          @click="$emit('add', p)"
        >
          {{
            lines.some((line) => String(line.product.id) === String(p.id))
              ? "已加入草稿"
              : "加入草稿"
          }}
          <span class="sr-only">：{{ p.name }}</span>
        </el-button>
      </article>
      <p v-if="!candidates.length">
        没有符合筛选的在售商品。可调整分类或名称；这不表示没有满足需求的设备。
      </p>
    </div>
  </section>
</template>
<style scoped>
.candidate-panel {
  margin: 24px 0;
}
.candidate-filters {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 12px;
}
.candidate-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  max-height: 520px;
  overflow: auto;
}
.candidate-card {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.candidate-card p,
.quantity-progress {
  font-size: 13px;
  line-height: 1.7;
}
.quantity-progress {
  padding-left: 20px;
}
.budget-overview {
  font-weight: 600;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}
@media (max-width: 600px) {
  .candidate-filters,
  .candidate-list {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
