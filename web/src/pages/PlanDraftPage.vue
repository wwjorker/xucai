<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, computed } from "vue";
import { useRoute, onBeforeRouteLeave } from "vue-router";
import { ElMessageBox, ElMessage } from "element-plus";
import { api, query } from "../api";
import { highlight } from "../chunks.js";
import { chunkSelectionError } from "../plan-chunks.js";
import { money, safeUrl, categoryOf } from "../domain";
import { parseRequirementQuantity } from "../requirements";
import PlanCandidatePicker from "../components/PlanCandidatePicker.vue";
import CandidateDraftImporter from "../components/CandidateDraftImporter.vue";
import AiPlanOrigin from "../components/AiPlanOrigin.vue";
import SemanticRetrievalPanel from "../components/SemanticRetrievalPanel.vue";
import {
  suggestedQuantity,
  draftBudget,
  catalogSaleUnavailable,
} from "../plan-candidates.js";
const route = useRoute(),
  requirementId = route.params.id;
const requirement = ref(null),
  products = ref([]),
  history = ref([]),
  total = ref(0),
  page = ref(1),
  selected = ref(null);
const busy = ref(false),
  loading = ref(true),
  error = ref(""),
  historyError = ref(""),
  detailError = ref("");
const form = reactive({ summary: "", items: [] }),
  pending = ref(null),
  saved = ref("");
const baseReloading = ref(false);
const candidateBusy = ref(false);
const candidatePending = ref(false);
const locked = computed(
  () =>
    busy.value ||
    baseReloading.value ||
    !!pending.value ||
    candidateBusy.value ||
    candidatePending.value,
);
const dirty = computed(
  () => JSON.stringify(form) !== saved.value || !!pending.value,
);
let alive = true,
  historyGeneration = 0,
  detailGeneration = 0;
async function request(path, options = {}) {
  return api("/trade/admin/" + path, { scope: "merchant", ...options });
}
async function loadHistory() {
  const generation = ++historyGeneration;
  historyError.value = "";
  try {
    const data = await request(
      `requirements/${requirementId}/plans?page=${page.value}&size=10`,
    );
    if (alive && generation === historyGeneration) {
      history.value = data.items;
      total.value = data.total;
    }
  } catch (e) {
    if (alive && generation === historyGeneration)
      historyError.value = e.message;
  }
}
async function readPlan(id) {
  const generation = ++detailGeneration;
  detailError.value = "";
  selected.value = null;
  try {
    const data = await request("plans/" + id);
    if (alive && generation === detailGeneration) selected.value = data;
  } catch (e) {
    if (alive && generation === detailGeneration) detailError.value = e.message;
  }
}
async function refreshBase() {
  if (locked.value) return;
  baseReloading.value = true;
  try {
    if (dirty.value && !(await discard())) return;
    loading.value = true;
    error.value = "";
    const req = await request("requirements/" + requirementId);
    // 商品选择逐页取得；目前仅少量SKU，仍不静默截断未来目录。
    let list = [],
      p = 1,
      data;
    do {
      data = await api(`/catalog/admin/products?page=${p++}&size=100`, {
        scope: "merchant",
      });
      list.push(...data.items);
    } while (list.length < data.total && p <= 100);
    if (catalogSaleUnavailable(list))
      throw new Error(
        "商品价格与库存暂不可用，请稍后重新读取；未替换当前草稿。",
      );
    if (list.length < Number(data.total))
      throw new Error("商品目录未读取完整，请稍后重新读取；未替换当前草稿。");
    if (!alive) return;
    requirement.value = req;
    products.value = list.filter(
      (p) => p.status === "PUBLISHED" && p.sale?.onSale,
    );
    form.summary = "";
    form.items = [];
    saved.value = JSON.stringify(form);
    pending.value = null;
    ++detailGeneration;
    selected.value = null;
    detailError.value = "";
    page.value = 1;
    await loadHistory();
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
    baseReloading.value = false;
  }
}
async function add(product) {
  if (
    locked.value ||
    requirement.value?.status !== "OPEN" ||
    form.items.some((i) => String(i.product.id) === String(product.id))
  )
    return;
  if (form.items.length >= 20) {
    error.value = "每份草稿最多20种商品";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const p = await api(`/catalog/admin/products/${product.id}/snapshot`, {
      scope: "merchant",
    });
    if (!alive) return;
    form.items.push({
      product: { ...p, sale: product.sale },
      quantity: suggestedQuantity(
        requirement.value.structured,
        form.items,
        p.categoryId,
      ),
      docId: "",
      quote: "",
      note: "",
      chunk: null,
      chunkText: "",
    });
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function documentFor(line) {
  return line.product.documents.find(
    (d) => String(d.id) === String(line.docId),
  );
}
function changeDoc(line) {
  if (line.chunk) dropChunk(line, "换了资料，原来的片段出处已取消");
  if (line.quote || line.note)
    ElMessage.info("摘录和说明已保留，请对照新资料重新核对。");
}
// 冻结的片段出处只在摘录与当时那段文字逐字相同时才成立；手改一个字就不再算"从片段选来的"。
function dropChunk(line, why) {
  line.chunk = null;
  line.chunkText = "";
  if (why) ElMessage.info(why);
}
function quoteEdited(line) {
  if (line.chunk && line.quote !== line.chunkText)
    dropChunk(line, "摘录已手工修改，不再标记为片段出处");
}

const picker = reactive({
  mode: "keyword",
  open: false,
  line: null,
  items: [],
  busy: false,
  error: "",
  keyword: "",
});
let pickerGeneration = 0;
function closePicker() {
  ++pickerGeneration;
  picker.open = false;
  picker.line = null;
  picker.items = [];
  picker.busy = false;
}
async function openPicker(line, keyword = "") {
  if (locked.value || !form.items.includes(line)) return;
  picker.open = true;
  picker.line = line;
  picker.mode = "keyword";
  picker.keyword = keyword;
  await loadChunks();
}
async function loadChunks() {
  if (!picker.open || !picker.line || locked.value) return;
  const generation = ++pickerGeneration,
    line = picker.line;
  const relevant = () =>
    alive &&
    picker.open &&
    picker.line === line &&
    generation === pickerGeneration;
  picker.busy = true;
  picker.error = "";
  picker.items = [];
  try {
    const data = await api(
      "/catalog/admin/doc-chunks?" +
        query({
          productId: line.product.id,
          includeUnpublished: true,
          keyword: picker.keyword.trim(),
          size: 50,
        }),
      { scope: "merchant" },
    );
    if (!relevant()) return;
    picker.items = data.items;
    if (!data.items.length)
      picker.error = data.dropped?.length
        ? "该商品的片段未通过原文复核，请先在商品编辑页重建片段。"
        : "没有匹配的片段。资料可能尚未建立片段，可在商品编辑页重建。";
  } catch (e) {
    if (!relevant()) return;
    picker.error = e.message;
    picker.items = [];
  } finally {
    if (relevant()) picker.busy = false;
  }
}
function useChunk(item) {
  const line = picker.line;
  if (locked.value || picker.busy || !picker.open || !form.items.includes(line))
    return;
  const problem = chunkSelectionError(line, item);
  if (problem) {
    picker.error = problem;
    return;
  }
  // 选片段时同时确定资料版本，避免编号与所选资料对不上。
  line.docId = item.docId;
  line.quote = item.text;
  line.chunkText = item.text;
  line.chunk = {
    chunkId: item.chunkId,
    ruleVersion: item.ruleVersion,
    charStart: item.charStart,
    charEnd: item.charEnd,
  };
  closePicker();
  ElMessage.success(
    "已插入片段原文，请补写引用说明；是否满足需求仍需你自己核实",
  );
}
function candidateCreated(plan) {
  if (!alive) return;
  ++detailGeneration;
  detailError.value = "";
  selected.value = plan;
  page.value = 1;
  loadHistory();
}
async function save() {
  if (
    busy.value ||
    baseReloading.value ||
    candidateBusy.value ||
    candidatePending.value
  )
    return;
  error.value = "";
  try {
    if (!pending.value) {
      if (!form.summary.trim() || !form.items.length)
        throw new Error("请填写草稿说明并选择商品");
      const items = form.items.map((line) => {
        const quantity = parseRequirementQuantity(
          line.quantity,
          line.product.name,
        );
        if (quantity == null) throw new Error("请填写每项商品件数");
        const doc = documentFor(line);
        if (line.docId && !doc)
          throw new Error(
            "所选资料已不在商品快照中，请重新读取商品；引用未保存。",
          );
        if (doc && (!line.quote.trim() || !line.note.trim()))
          throw new Error("选择资料后，请填写原文摘录和引用说明");
        return {
          productId: line.product.id,
          expectedSpecVersion: line.product.specVersion,
          expectedSaleVersion: line.product.sale.saleVersion,
          quantity,
          evidence: doc
            ? [
                {
                  docId: doc.id,
                  version: doc.version,
                  quote: line.quote.trim(),
                  note: line.note.trim(),
                  // 只有摘录与片段原文逐字相同才带出处，服务端还会再核一遍偏移
                  chunk:
                    line.chunk && line.quote.trim() === line.chunkText
                      ? line.chunk
                      : null,
                },
              ]
            : [],
        };
      });
      if (items.reduce((n, i) => n + i.quantity, 0) > 10000)
        throw new Error("设备总件数不能超过10000");
      pending.value = {
        requestId: crypto.randomUUID(),
        expectedRequirementRevision: requirement.value.revision,
        summary: form.summary.trim(),
        items,
      };
    }
    busy.value = true;
    const data = await request(`requirements/${requirementId}/plans`, {
      method: "POST",
      body: pending.value,
    });
    // 成功响应已持久化；后续历史刷新失败不能重新提交相同编辑内容。
    pending.value = null;
    saved.value = JSON.stringify(form);
    ++detailGeneration;
    detailError.value = "";
    selected.value = data;
    ElMessage.success("草稿已保存，尚未核实");
    page.value = 1;
    await loadHistory();
  } catch (e) {
    error.value = e.message;
    if ([400, 403, 404, 409].includes(e.code)) pending.value = null;
  } finally {
    busy.value = false;
  }
}
async function discard() {
  try {
    await ElMessageBox.confirm(
      "当前草稿有未保存的输入，重新读取会清空这些输入。",
      "保留当前编辑？",
      { confirmButtonText: "放弃输入", cancelButtonText: "继续编辑" },
    );
    return true;
  } catch {
    return false;
  }
}
onBeforeRouteLeave(async () => {
  if (busy.value) return false;
  if (pending.value) {
    ElMessage.warning("保存结果待核对，请先重试本次保存");
    return false;
  }
  return !dirty.value || (await discard());
});
function unload(e) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onMounted(async () => {
  saved.value = JSON.stringify(form);
  await refreshBase();
  loadHistory();
  window.addEventListener("beforeunload", unload);
});
onBeforeUnmount(() => {
  alive = false;
  closePicker();
  window.removeEventListener("beforeunload", unload);
});
const estimate = computed(() =>
  requirement.value
    ? draftBudget(requirement.value.structured, form.items).amount
    : null,
);
const evidenceTerms = computed(() =>
  [
    ...new Set([
      ...(requirement.value?.structured.mustHave || []),
      ...(requirement.value?.structured.optional || []),
    ]),
  ]
    .filter((term) => term.trim() && term.length <= 80)
    .slice(0, 8),
);
function findEvidence(keyword) {
  picker.keyword = keyword;
  loadChunks();
}
</script>
<template>
  <div class="page-container plan-workspace">
    <RouterLink
      :to="'/merchant/requirements/' + requirementId"
      class="back-link"
      >返回客户需求</RouterLink
    >
    <section class="page-heading">
      <div>
        <p class="eyebrow">PROCUREMENT PLAN</p>
        <h1>编制采购方案</h1>
        <p>保留商品、金额与资料的当时版本，为后续核实准备清单。</p>
      </div>
      <el-button :disabled="locked" @click="refreshBase"
        >读取最新需求与商品</el-button
      >
    </section>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="pending"
      title="本次保存结果待核对，输入已锁定。重试会使用同一保存标识，避免重复草稿。"
      type="warning"
      :closable="false"
    />
    <p v-if="loading">正在读取需求与商品…</p>
    <template v-else-if="requirement">
      <CandidateDraftImporter
        :requirement="requirement"
        :disabled="busy || baseReloading || !!pending || dirty"
        @busy="candidateBusy = $event"
        @pending="candidatePending = $event"
        @created="candidateCreated"
      />
      <section class="surface plan-brief">
        <p class="eyebrow">需求第 {{ requirement.revision }} 版</p>
        <h2>{{ requirement.title }}</h2>
        <p>{{ requirement.rawText }}</p>
        <p>
          总预算：¥ {{ money(requirement.structured.budgetCents) }} ·
          {{ requirement.structured.usage || "用途待补充" }}
        </p>
        <p>
          必须满足：{{
            requirement.structured.mustHave.join("；") || "尚未填写"
          }}
        </p>
        <el-alert
          title="商家人工草稿，尚未核实；保存不会预占库存，也不能直接下单。"
          type="info"
          :closable="false"
        />
      </section>
      <div class="plan-columns">
        <section class="surface plan-editor">
          <h2>商品组合</h2>
          <p v-if="requirement.status !== 'OPEN'" class="error-text">
            需求已关闭或已成单，不能保存新草稿。
          </p>
          <el-form
            label-position="top"
            :disabled="locked || requirement.status !== 'OPEN'"
          >
            <el-form-item label="草稿说明"
              ><el-input
                v-model="form.summary"
                aria-label="草稿说明"
                maxlength="500"
                placeholder="例如：优先考虑有线连接的会议设备组合"
            /></el-form-item>
            <PlanCandidatePicker
              :products="products"
              :details="requirement.structured"
              :lines="form.items"
              :disabled="locked || requirement.status !== 'OPEN'"
              @add="add"
            />
            <article
              v-for="(line, index) in form.items"
              :key="line.product.id"
              class="plan-line"
            >
              <div class="section-bar">
                <h3>{{ line.product.name }}</h3>
                <el-button text @click="form.items.splice(index, 1)"
                  >移除</el-button
                >
              </div>
              <p>
                {{ categoryOf(line.product.categoryId).name }} · 单价 ¥
                {{ money(line.product.sale.price) }} · 规格第
                {{ line.product.specVersion }} 版
              </p>
              <el-form-item label="采购件数"
                ><el-input
                  v-model="line.quantity"
                  :aria-label="line.product.name + '件数'"
                  inputmode="numeric"
                  maxlength="5"
              /></el-form-item>
              <el-form-item label="资料依据（可暂留空，保存后仍待核实）"
                ><el-select
                  v-model="line.docId"
                  :aria-label="line.product.name + '资料'"
                  clearable
                  placeholder="选择资料原文"
                  @change="changeDoc(line)"
                  ><el-option
                    v-for="d in line.product.documents"
                    :key="d.id"
                    :label="d.title + ' · 第' + d.version + '版'"
                    :value="d.id" /></el-select
              ></el-form-item>
              <div class="chunk-pick">
                <el-button
                  size="small"
                  :disabled="busy"
                  @click="openPicker(line)"
                  >从资料片段选取原文</el-button
                >
                <span v-if="line.chunk" class="chunk-pick-tag"
                  >已记录出处 {{ line.chunk.chunkId }} · 原文第
                  {{ line.chunk.charStart }}–{{ line.chunk.charEnd }} 字 · 规则
                  {{ line.chunk.ruleVersion }}</span
                >
                <span v-else class="muted"
                  >也可以手工摘录；从片段选取会额外记录原文位置，便于以后复核。</span
                >
              </div>
              <template v-if="documentFor(line)"
                ><details>
                  <summary>展开资料原文</summary>
                  <pre class="plan-document">{{
                    documentFor(line).content
                  }}</pre>
                </details>
                <el-form-item label="原文摘录"
                  ><el-input
                    v-model="line.quote"
                    type="textarea"
                    :aria-label="line.product.name + '原文摘录'"
                    maxlength="1000"
                    @input="quoteEdited(line)" /></el-form-item
                ><el-form-item label="引用说明（尚未验证）"
                  ><el-input
                    v-model="line.note"
                    :aria-label="line.product.name + '引用说明'"
                    maxlength="500" /></el-form-item
              ></template>
            </article>
          </el-form>
          <div class="section-bar">
            <strong>{{
              estimate == null
                ? "草稿估算：请补齐有效件数"
                : "草稿估算 ¥ " + money(estimate)
            }}</strong
            ><el-button
              type="primary"
              :disabled="
                busy ||
                baseReloading ||
                candidateBusy ||
                candidatePending ||
                requirement.status !== 'OPEN' ||
                (!dirty && !pending)
              "
              @click="save"
              >{{ pending ? "重试本次保存" : "保存为新草稿" }}</el-button
            >
          </div>
        </section>
        <aside class="surface plan-history">
          <div class="section-bar">
            <h2>草稿版本</h2>
            <el-button text @click="loadHistory">刷新列表</el-button>
          </div>
          <p v-if="historyError" class="error-text">{{ historyError }}</p>
          <p v-if="!history.length">还没有草稿。</p>
          <button
            v-for="p in history"
            :key="p.id"
            class="plan-history-row"
            @click="readPlan(p.id)"
          >
            <strong
              >方案 {{ p.planNo }} ·
              {{
                p.effectiveStatus === "ORDERED"
                  ? "已建立订单"
                  : p.effectiveStatus === "STALE"
                    ? "需求已变化"
                    : p.status === "PENDING_CONFIRM"
                      ? "已发布待确认"
                      : "待核实草稿"
              }}</strong
            ><span>{{ p.summary }}</span
            ><span
              >¥ {{ money(p.totalAmount) }} · 需求第
              {{ p.requirementRevision }} 版</span
            ></button
          ><el-pagination
            v-if="total > 10"
            v-model:current-page="page"
            :page-size="10"
            :total="total"
            layout="prev, pager, next"
            @current-change="loadHistory"
          />
        </aside>
      </div>
    </template>
    <p v-if="detailError" class="error-text">{{ detailError }}</p>
    <section v-if="selected" class="surface plan-snapshot">
      <RouterLink
        :to="
          '/merchant/plans/' +
          selected.id +
          (selected.status === 'DRAFT' ? '/review' : '/released')
        "
        >{{
          selected.status === "DRAFT"
            ? "逐项核实此草稿 →"
            : "查看已发布核实记录 →"
        }}</RouterLink
      >
      <p class="eyebrow">
        已保存方案 {{ selected.planNo }} ·
        {{ selected.source === "AI" ? "商家采用AI候选" : "商家人工编制" }}
      </p>
      <AiPlanOrigin
        :origin="selected.snapshot.aiOrigin"
        :published="selected.status !== 'DRAFT'"
      />
      <h2>{{ selected.summary }}</h2>
      <el-alert
        v-if="selected.effectiveStatus === 'STALE'"
        title="需求已变化，此草稿已过期。请基于最新需求重新编制。"
        type="warning"
        :closable="false"
      />
      <p>
        需求第 {{ selected.requirementRevision }} 版 · 合计 ¥
        {{ money(selected.totalAmount) }} · 保存后内容不变
      </p>
      <ul>
        <li v-for="w in selected.snapshot.warnings" :key="w">{{ w }}</li>
      </ul>
      <article
        v-for="i in selected.snapshot.items"
        :key="i.productId"
        class="plan-line"
      >
        <h3>{{ i.name }} × {{ i.quantity }}</h3>
        <p>单价 ¥ {{ money(i.unitPrice) }} · 规格第 {{ i.specVersion }} 版</p>
        <details>
          <summary>查看保存时规格</summary>
          <pre class="plan-document">{{
            JSON.stringify(i.specJson, null, 2)
          }}</pre>
        </details>
        <div v-for="e in i.evidence" :key="e.docId" class="plan-evidence">
          <small v-if="e.chunk" class="chunk-origin"
            >出处 {{ e.chunk.chunkId }} · 原文第 {{ e.chunk.charStart }}–{{
              e.chunk.charEnd
            }}
            字 · 规则
            {{ e.chunk.ruleVersion }}（保存时逐字核对过，仅证明出处）</small
          >
          <strong>{{ e.title }} · 第 {{ e.version }} 版 · 待核实</strong>
          <blockquote>{{ e.quote }}</blockquote>
          <p>商家说明：{{ e.note }}</p>
          <details>
            <summary>展开保存时资料原文</summary>
            <pre class="plan-document">{{ e.content }}</pre>
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
    <el-dialog
      v-model="picker.open"
      @close="closePicker"
      title="从资料片段选取原文"
      width="min(720px, 92vw)"
    >
      <el-alert type="info" :closable="false" show-icon class="panel-alert">
        <template #title>片段只证明出处，不证明这款商品满足需求</template>
        <div>
          关键词按字面查找，语义检索按相似度排序。选中后会把整段原文填进摘录并记录它在原文里的位置；
          引用说明仍要你自己写，核实结论仍要你自己判断。
        </div>
      </el-alert>
      <el-radio-group
        v-model="picker.mode"
        :disabled="locked"
        aria-label="资料检索方式"
        class="panel-alert"
      >
        <el-radio-button value="keyword">关键词查找</el-radio-button>
        <el-radio-button value="semantic">语义检索</el-radio-button>
      </el-radio-group>
      <SemanticRetrievalPanel
        v-if="picker.open && picker.line && picker.mode === 'semantic'"
        :key="picker.line.product.id"
        :product-id="picker.line.product.id"
        :disabled="locked || picker.busy"
        selectable
        @select="useChunk"
      />
      <template v-if="picker.mode === 'keyword'">
        <div v-if="evidenceTerms.length" class="evidence-terms">
          <p>用需求条件查原文（按字面关键词查找，可自行缩短关键词）：</p>
          <el-button
            v-for="term in evidenceTerms"
            :key="term"
            size="small"
            :disabled="picker.busy || locked"
            @click="findEvidence(term)"
            >{{ term }}</el-button
          >
        </div>
        <form class="search-form" @submit.prevent="loadChunks">
          <el-input
            v-model="picker.keyword"
            clearable
            placeholder="按关键词筛选，空格分隔"
            aria-label="片段关键词"
            @clear="loadChunks"
          />
          <el-button native-type="submit" :loading="picker.busy"
            >查找</el-button
          >
        </form>
        <el-alert
          v-if="picker.error"
          :title="picker.error"
          type="warning"
          :closable="false"
          show-icon
          class="panel-alert"
        />
        <ol v-else class="pick-list">
          <li v-for="c in picker.items" :key="c.chunkId">
            <div class="pick-head">
              <strong>{{ c.docTitle }}</strong>
              <small class="muted"
                >第 {{ c.version }} 版 · 第 {{ c.chunkNo }} 段<template
                  v-if="c.headingPath"
                >
                  · {{ c.headingPath }}</template
                ></small
              >
            </div>
            <p class="pick-text">
              <span
                v-for="(part, i) in highlight(c.text, c.matches)"
                :key="i"
                :class="{ hit: part.hit }"
                >{{ part.text }}</span
              >
            </p>
            <div class="pick-foot">
              <code>{{ c.chunkId }}</code>
              <small class="muted"
                >原文第 {{ c.charStart }}–{{ c.charEnd }} 字</small
              >
              <small v-if="c.text.length > 1000"
                >本段超过1000字，请关闭窗口后手工摘录所需原文。</small
              >
              <el-button
                size="small"
                type="primary"
                :disabled="picker.busy || locked || c.text.length > 1000"
                @click="useChunk(c)"
                >用作摘录</el-button
              >
            </div>
          </li>
        </ol>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.evidence-terms {
  margin: 12px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.evidence-terms p {
  width: 100%;
  margin: 0;
}
.evidence-terms .el-button {
  margin: 0;
  white-space: normal;
  height: auto;
  max-width: 100%;
}
.plan-workspace {
  padding-bottom: 64px;
}
.plan-brief,
.plan-editor,
.plan-history,
.plan-snapshot {
  padding: 28px;
  margin-bottom: 24px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: white;
}
.plan-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 24px;
}
.plan-picker {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 20px 0;
}
.plan-picker .el-button {
  white-space: normal;
  height: auto;
  padding: 12px;
  max-width: 100%;
  text-align: left;
}
.plan-line {
  border-top: 1px solid #e5eae6;
  padding: 20px 0;
}
.plan-document {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: inherit;
  background: #f5f7f4;
  padding: 16px;
  max-height: 360px;
  overflow: auto;
}
.plan-evidence {
  margin: 16px 0;
  padding: 16px;
  background: #f5f7f4;
  border-radius: 8px;
}
.plan-history-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
  padding: 16px 0;
  border: 0;
  border-bottom: 1px solid #e5eae6;
  background: none;
  width: 100%;
  color: inherit;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.plan-snapshot {
  overflow-wrap: anywhere;
}
.plan-snapshot li {
  margin: 8px 0;
}
summary {
  cursor: pointer;
  margin: 12px 0;
}
.section-bar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}
.error-text {
  color: #a5372c;
}
.chunk-pick {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 10px 0;
  font-size: 12px;
}
.chunk-pick-tag,
.chunk-origin {
  color: #4c665b;
  background: #edf3ee;
  border-radius: 5px;
  padding: 2px 8px;
  overflow-wrap: anywhere;
}
.chunk-origin {
  display: block;
  margin-bottom: 6px;
  font-size: 11px;
}
.pick-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
  max-height: 46vh;
  overflow: auto;
}
.pick-list li {
  border: 1px solid #e4e9e2;
  border-radius: 10px;
  padding: 12px 14px;
  overflow-wrap: anywhere;
}
.pick-head small {
  display: block;
}
.pick-text {
  margin: 8px 0;
  line-height: 1.75;
  white-space: pre-wrap;
}
.pick-text .hit {
  background: #ffe9a8;
  border-radius: 3px;
}
.pick-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.pick-foot code {
  font-size: 11px;
  background: #f2f5f1;
  border-radius: 5px;
  padding: 2px 6px;
}
.pick-foot .el-button {
  margin-left: auto;
}
.plan-brief p {
  overflow-wrap: anywhere;
}
@media (max-width: 800px) {
  .plan-columns {
    grid-template-columns: 1fr;
  }
  .plan-brief,
  .plan-editor,
  .plan-history,
  .plan-snapshot {
    padding: 18px;
  }
  .section-bar {
    flex-wrap: wrap;
  }
  .plan-picker > span {
    max-width: 100%;
  }
}
</style>
