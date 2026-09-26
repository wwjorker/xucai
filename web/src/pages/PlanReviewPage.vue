<script setup>
import AiPlanOrigin from "../components/AiPlanOrigin.vue";
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api";
import { money, safeUrl } from "../domain";
const route = useRoute(),
  router = useRouter(),
  id = route.params.id;
const merchant = !!route.meta.merchant,
  reviewing = route.name === "plan-review",
  scope = merchant ? "merchant" : "buyer";
const data = ref(null),
  busy = ref(false),
  error = ref(""),
  pending = ref(null),
  initialized = ref(false),
  saved = ref("");
const form = reactive({ note: "", checks: [] });
let alive = true,
  bypass = false;
const plan = computed(() => (reviewing ? data.value?.plan : data.value)),
  current = computed(() => data.value?.currentCheck);
const dirty = computed(
  () => initialized.value && JSON.stringify(form) !== saved.value,
);
const references = computed(
  () =>
    plan.value?.snapshot.items.flatMap((i) =>
      i.evidence.map((e) => ({
        value: i.productId + ":" + e.docId,
        productId: i.productId,
        docId: e.docId,
        name: i.name,
        title: e.title,
        quote: e.quote,
      })),
    ) || [],
);
const canPublish = computed(
  () =>
    reviewing &&
    plan.value?.status === "DRAFT" &&
    current.value?.status === "READY" &&
    !data.value?.releasedPlanId,
);
const stateName = (s) =>
  ({
    READY: "本次检查通过",
    ORDERED: "已建立订单",
    BLOCKED: "需要重新核实",
    STALE: "需求已变化",
    UNAVAILABLE: "暂时无法完成检查",
  })[s] || "正在检查";
async function load() {
  if (busy.value || pending.value) return;
  busy.value = true;
  error.value = "";
  if (data.value)
    data.value.currentCheck = {
      status: "UNAVAILABLE",
      reasons: ["正在重新检查，请稍候"],
    };
  try {
    const result = await api(
      merchant
        ? `/trade/admin/plans/${id}/${reviewing ? "review" : "released"}`
        : `/trade/plans/${id}`,
      { scope },
    );
    if (!alive) return;
    data.value = result;
    if (reviewing && !initialized.value) {
      form.checks = result.conditions.map((c) => ({
        ...c,
        verdict: "UNKNOWN",
        reason: "",
        selected: [],
      }));
      initialized.value = true;
      saved.value = JSON.stringify(form);
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function publish() {
  if (busy.value) return;
  error.value = "";
  try {
    if (!pending.value) {
      if (!canPublish.value) throw new Error("当前方案未通过检查，不能发布");
      if (
        !form.note.trim() ||
        form.checks.some(
          (c) => c.verdict !== "MET" || !c.reason.trim() || !c.selected.length,
        )
      )
        throw new Error("请逐项判断、说明依据并选择摘录；未知或冲突项不能发布");
      pending.value = {
        requestId: crypto.randomUUID(),
        note: form.note.trim(),
        checks: form.checks.map((c) => ({
          key: c.key,
          verdict: c.verdict,
          reason: c.reason.trim(),
          references: c.selected.map((value) => {
            const [productId, docId] = value.split(":");
            return { productId, docId };
          }),
        })),
      };
    }
    busy.value = true;
    const result = await api(`/trade/admin/plans/${id}/publish`, {
      scope: "merchant",
      method: "POST",
      body: pending.value,
    });
    pending.value = null;
    saved.value = JSON.stringify(form);
    bypass = true;
    ElMessage.success("已生成独立待确认版本");
    await router.replace(`/merchant/plans/${result.id}/released`);
  } catch (e) {
    error.value = e.message;
    if ([400, 403, 404, 409].includes(e.code)) pending.value = null;
    if (data.value && [409, 503].includes(e.code))
      data.value.currentCheck = {
        status: e.code === 409 ? "BLOCKED" : "UNAVAILABLE",
        reasons: [e.message],
      };
  } finally {
    busy.value = false;
  }
}
onBeforeRouteLeave(async () => {
  if (bypass) return true;
  if (busy.value || pending.value) {
    ElMessage.warning("请先核对本次发布结果");
    return false;
  }
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "离开后未发布的核实输入会丢失。",
      "离开核实页面？",
      { confirmButtonText: "离开", cancelButtonText: "继续核实" },
    );
    return true;
  } catch {
    return false;
  }
});
function unload(e) {
  if (dirty.value || pending.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onMounted(() => {
  load();
  window.addEventListener("beforeunload", unload);
});
onBeforeUnmount(() => {
  alive = false;
  window.removeEventListener("beforeunload", unload);
});
</script>
<template>
  <div class="page-container review-page">
    <RouterLink
      v-if="plan"
      class="back-link"
      :to="
        (merchant ? '/merchant' : '') +
        '/requirements/' +
        plan.requirementId +
        '/plans'
      "
      >返回方案列表</RouterLink
    >
    <section class="page-heading">
      <div>
        <p class="eyebrow">PLAN REVIEW</p>
        <h1>{{ reviewing ? "逐项核实采购方案" : "采购方案与核实依据" }}</h1>
        <p>
          {{
            reviewing
              ? "说明摘录怎样支持条件，以及适用于哪些商品。"
              : "核对商品组合、金额与商家说明；当前页面仅供查看与比较。"
          }}
        </p>
      </div>
      <el-button :disabled="busy || !!pending" @click="load"
        >重新检查当前信息</el-button
      >
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert
      v-if="pending"
      title="发布结果待核对，输入已锁定；重试同一请求不会重复发布。"
      type="warning"
      :closable="false"
    />
    <template v-if="plan">
      <AiPlanOrigin
        :origin="plan.snapshot.aiOrigin"
        :published="plan.status !== 'DRAFT'"
      />
      <section class="review-card">
        <h2>{{ plan.summary }}</h2>
        <p>
          方案 {{ plan.planNo }} · 需求第 {{ plan.requirementRevision }} 版 · ¥
          {{ money(plan.totalAmount) }}
        </p>
        <h3>{{ stateName(current?.status) }}</h3>
        <ul>
          <li v-for="r in current?.reasons" :key="r">{{ r }}</li>
        </ul>
        <p class="muted">
          检查时间：{{
            current?.checkedAt || "等待重新检查"
          }}。本次结果不预占库存，下单时仍须复查。
        </p>
        <p v-if="plan.status === 'DRAFT'">这是尚未发布的商家草稿。</p>
        <p v-else>
          商家人工核实时间：{{
            plan.verifiedAt
          }}。语义判断来自商家记录，系统只核对出处、版本和明确数值条件。
        </p>
      </section>
      <section class="review-card">
        <h2>采购条件</h2>
        <p>{{ plan.snapshot.requirement.rawText }}</p>
        <p>
          总预算 ¥
          {{ money(plan.snapshot.requirement.structured.budgetCents) }} ·
          {{ plan.snapshot.requirement.structured.usage }}
        </p>
        <p>
          必须满足：{{
            plan.snapshot.requirement.structured.mustHave.join("；") || "未填写"
          }}
        </p>
        <p>
          偏好（不作通过条件）：{{
            plan.snapshot.requirement.structured.optional.join("；") || "未填写"
          }}
        </p>
      </section>
      <section class="review-card">
        <h2>商品与保存时资料</h2>
        <article
          v-for="i in plan.snapshot.items"
          :key="i.productId"
          class="review-item"
        >
          <h3>
            {{ i.name }} × {{ i.quantity }} · ¥ {{ money(i.unitPrice) }}/件
          </h3>
          <p>规格第 {{ i.specVersion }} 版</p>
          <details>
            <summary>查看规格快照</summary>
            <pre>{{ JSON.stringify(i.specJson, null, 2) }}</pre>
          </details>
          <p v-if="!i.evidence.length">尚无资料摘录，需补充新草稿后再核实。</p>
          <div v-for="e in i.evidence" :key="e.docId" class="review-evidence">
            <strong>{{ e.title }} · 第 {{ e.version }} 版</strong>
            <small v-if="e.chunk" class="evidence-origin"
              >出处 {{ e.chunk.chunkId }} · 原文第 {{ e.chunk.charStart }}–{{
                e.chunk.charEnd
              }}
              字 · 切分规则
              {{
                e.chunk.ruleVersion
              }}。保存时已逐字核对位置，仅证明这句话出自该资料。</small
            >
            <blockquote>{{ e.quote }}</blockquote>
            <p>草稿说明：{{ e.note }}</p>
            <details>
              <summary>查看保存时原文</summary>
              <pre>{{ e.content }}</pre>
            </details>
            <a
              v-if="safeUrl(e.sourceUrl)"
              :href="safeUrl(e.sourceUrl)"
              target="_blank"
              rel="noopener noreferrer"
              >查看来源网站 ↗</a
            >
          </div>
        </article>
      </section>
      <section v-if="reviewing" class="review-card">
        <h2>人工核实记录</h2>
        <p>
          预算和分类件数由系统检查。以下条件由商家判断；一件商品的引用不能自动代表整套方案。
        </p>
        <RouterLink
          v-if="data.releasedPlanId"
          :to="'/merchant/plans/' + data.releasedPlanId + '/released'"
          >此草稿已有待确认版本，点击查看 →</RouterLink
        >
        <el-form
          label-position="top"
          :disabled="
            busy ||
            !!pending ||
            !!data.releasedPlanId ||
            plan.status !== 'DRAFT'
          "
        >
          <article v-for="c in form.checks" :key="c.key" class="review-item">
            <h3>
              {{ c.key === "usage" ? "使用场景" : "必须条件" }}：{{ c.text }}
            </h3>
            <el-form-item label="商家判断"
              ><el-select v-model="c.verdict" :aria-label="c.key + '判断'"
                ><el-option label="尚未确定" value="UNKNOWN" /><el-option
                  label="存在冲突"
                  value="CONFLICT" /><el-option
                  label="依据下述说明满足"
                  value="MET" /></el-select
            ></el-form-item>
            <el-form-item label="支持该判断的草稿摘录"
              ><el-checkbox-group
                v-model="c.selected"
                :aria-label="c.key + '引用'"
                ><el-checkbox
                  v-for="r in references"
                  :key="r.value"
                  :value="r.value"
                  ><span>{{ r.name }}：{{ r.quote }}</span></el-checkbox
                ></el-checkbox-group
              ></el-form-item
            >
            <el-form-item label="如何支持条件、适用于哪些商品"
              ><el-input
                v-model="c.reason"
                type="textarea"
                :aria-label="c.key + '核实说明'"
                maxlength="1000"
            /></el-form-item>
          </article>
          <el-form-item label="整体核实说明"
            ><el-input
              v-model="form.note"
              type="textarea"
              aria-label="整体核实说明"
              maxlength="1000"
          /></el-form-item> </el-form
        ><el-button
          type="primary"
          :disabled="busy || (!pending && !canPublish)"
          @click="publish"
          >{{ pending ? "重试本次发布" : "生成待确认版本" }}</el-button
        >
      </section>
      <section v-else-if="plan.review" class="review-card">
        <h2>商家逐项核实</h2>
        <p>{{ plan.review.note }}</p>
        <article
          v-for="c in plan.review.checks"
          :key="c.key"
          class="review-item"
        >
          <h3>{{ c.text }}</h3>
          <p>商家判断：依据下述说明满足</p>
          <p>{{ c.reason }}</p>
          <div v-for="r in c.references" :key="r.productId + ':' + r.docId">
            <strong>适用商品：{{ r.productName }}</strong>
            <blockquote>{{ r.quote }}</blockquote>
            <p>{{ r.title }} · 第 {{ r.version }} 版</p>
          </div>
        </article>
        <el-alert
          title="核对商家说明与当前信息后，可进入确认页填写收货信息。"
          type="info"
          :closable="false"
        />
        <p v-if="plan.orderId">
          <RouterLink
            :to="
              (merchant ? '/merchant/purchase-orders/' : '/purchase-orders/') +
              plan.orderId
            "
            >查看已建立的订单 →</RouterLink
          >
        </p>
        <p v-else-if="!merchant && plan.status === 'PENDING_CONFIRM'">
          <RouterLink :to="'/plans/' + id + '/confirm'"
            >核对收货信息并确认订单 →</RouterLink
          >
        </p>
      </section>
    </template>
  </div>
</template>
<style scoped>
.review-page {
  padding-bottom: 64px;
}
.review-card {
  background: white;
  border: 1px solid #e3e8e2;
  border-radius: 12px;
  padding: 28px;
  margin: 22px 0;
  overflow-wrap: anywhere;
}
.review-item {
  border-top: 1px solid #e3e8e2;
  padding: 20px 0;
}
.evidence-origin {
  display: block;
  margin: 4px 0 6px;
  font-size: 11px;
  color: #4c665b;
  background: #edf3ee;
  border-radius: 5px;
  padding: 3px 8px;
  overflow-wrap: anywhere;
}
.review-evidence {
  background: #f5f7f3;
  padding: 18px;
  margin: 12px 0;
  border-radius: 8px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: inherit;
  max-height: 360px;
  overflow: auto;
}
summary {
  cursor: pointer;
  margin: 12px 0;
}
blockquote {
  margin: 12px 0;
  padding: 10px 16px;
  border-left: 3px solid #648678;
}
.muted {
  color: #687a72;
  font-size: 13px;
}
.el-checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.el-checkbox {
  height: auto;
  white-space: normal;
  align-items: flex-start;
}
:deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
li {
  margin: 8px 0;
}
@media (max-width: 600px) {
  .review-card {
    padding: 18px;
  }
}
</style>
