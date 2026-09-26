<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api";
import { categories, toCents, money, categoryOf } from "../domain";
import {
  requirementItems,
  requirementTotal,
  parseRequirementQuantity,
  prepareRequirementQuantities,
  legacyRequirementTotal,
} from "../requirements";
import Icon from "../components/Icon.vue";
import CandidateReference from "../components/CandidateReference.vue";
import { suggestionBlockReason, suggestionForm } from "../analysisSuggestion";
import { assistantRequirementForm } from "../assistantRequirement";
const route = useRoute(),
  router = useRouter(),
  id = computed(() => route.params.id),
  merchant = computed(() => !!route.meta.merchant);
const scope = computed(() => (merchant.value ? "merchant" : "buyer")),
  prefix = computed(() =>
    merchant.value ? "/merchant/requirements" : "/requirements",
  );
const saved = ref(""),
  item = ref(null),
  loading = ref(true),
  busy = ref(false),
  error = ref(""),
  conflict = ref(false);
const runs = ref([]),
  runError = ref(""),
  capability = ref({ available: false, message: "正在检查需求整理服务" });
const check = ref(null),
  checkError = ref(""),
  checking = ref(false);
const assistantDraft = ref(null);
let checkGeneration = 0;
const form = reactive({
  title: "",
  rawText: "",
  budget: "",
  items: [],
  usage: "",
  mustHave: "",
  optional: "",
});
const selectedCategories = computed({
  get: () => form.items.map((item) => item.categoryId),
  set: (ids) => {
    form.items = ids.map(
      (categoryId) =>
        form.items.find((item) => item.categoryId === categoryId) || {
          categoryId,
          quantity: undefined,
        },
    );
  },
});
const totalQuantity = computed(() => requirementTotal(form.items));
const legacyTotal = computed(() => legacyRequirementTotal(item.value));
const snapshot = () => JSON.stringify(form),
  dirty = computed(
    () => !merchant.value && !loading.value && snapshot() !== saved.value,
  );
let bypass = false,
  alive = true,
  reloading = false,
  poll;
const statusText = computed(() =>
  item.value?.status === "CLOSED"
    ? "已关闭"
    : item.value?.status === "ORDERED"
      ? "已下单"
      : "进行中",
);
const states = {
  RUNNING: "正在整理",
  DONE: "整理完成",
  FAILED: "未完成",
  CANCELED: "已取消",
};
async function load() {
  loading.value = true;
  error.value = "";
  conflict.value = false;
  try {
    if (id.value) {
      const data = await api(
        "/trade/" + (merchant.value ? "admin/" : "") + "requirements/" + id.value,
        { scope: scope.value },
      );
      item.value = data;
      const d = data.structured;
      Object.assign(form, {
        title: data.title,
        rawText: data.rawText,
        budget: d.budgetCents ? String(Number(d.budgetCents) / 100) : "",
        items: requirementItems(d),
        usage: d.usage,
        mustHave: d.mustHave.join("\n"),
        optional: d.optional.join("\n"),
      });
    } else if (!merchant.value && route.params.session && route.params.turn) {
      const sourcePath = "/requirements/from-assistant/" + encodeURIComponent(route.params.session) + "/" + encodeURIComponent(route.params.turn);
      const link = await api("/trade" + sourcePath, { scope: "buyer" });
      if (!alive) return;
      if (link.requirementId != null) {
        bypass = true;
        await router.replace("/requirements/" + link.requirementId);
        return;
      }
      const turn = await api("/ai/assistant/sessions/" + encodeURIComponent(route.params.session) + "/turns/" + encodeURIComponent(route.params.turn), { scope: "buyer" });
      if (!alive) return;
      saved.value = snapshot();
      Object.assign(form, assistantRequirementForm(turn));
      assistantDraft.value = turn.result.requirementDraft;
      return;
    }
    saved.value = snapshot();
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function loadCheck() {
  if (!id.value || !alive) return;
  const generation = ++checkGeneration;
  checking.value = true;
  checkError.value = "";
  try {
    const data = await api(
      "/trade/" +
        (merchant.value ? "admin/" : "") +
        "requirements/" +
        id.value +
        "/check",
      { scope: scope.value },
    );
    if (alive && generation === checkGeneration) check.value = data;
  } catch (e) {
    if (alive && generation === checkGeneration) {
      checkError.value = e.message;
      check.value = null;
    }
  } finally {
    if (generation === checkGeneration) checking.value = false;
  }
}
async function loadRuns() {
  if (!id.value || merchant.value || !alive) return;
  clearTimeout(poll);
  runError.value = "";
  try {
    runs.value = await api("/ai/runs?requirementId=" + id.value, {
      scope: "buyer",
    });
    if (alive && runs.value.some((r) => r.status === "RUNNING"))
      poll = setTimeout(loadRuns, 2000);
  } catch (e) {
    runError.value = e.message;
  }
}
async function capabilities() {
  try {
    capability.value = await api("/ai/runs/capability", { scope: "buyer" });
  } catch {
    capability.value = {
      available: false,
      message: "需求整理服务暂不可用，采购需求仍可保存",
    };
  }
}
function lines(value) {
  return value
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);
}
async function save() {
  error.value = "";
  conflict.value = false;
  try {
    if (!form.title.trim() || !form.rawText.trim())
      throw new Error("请填写需求标题与采购需求");
    const submittedItems = form.items.map((line) => ({
      categoryId: line.categoryId,
      quantity: parseRequirementQuantity(
        line.quantity,
        categoryOf(line.categoryId).name,
      ),
    }));
    const { replacesLegacyTotal, ...quantities } = prepareRequirementQuantities(
      item.value?.structured,
      submittedItems,
    );
    const structured = {
      budgetCents: form.budget === "" ? null : toCents(form.budget, "总预算"),
      ...quantities,
      usage: form.usage,
      mustHave: lines(form.mustHave),
      optional: lines(form.optional),
    };
    if (structured.mustHave.length > 20 || structured.optional.length > 20)
      throw new Error("每组条件最多20项");
    if (
      submittedItems.reduce((sum, item) => sum + (item.quantity ?? 0), 0) >
      10000
    )
      throw new Error("设备总件数不能超过10000");
    const submitted = snapshot();
    busy.value = true;
    if (replacesLegacyTotal) {
      try {
        await ElMessageBox.confirm(
          `原记录总共 ${item.value.structured.quantity} 件。保存后将改为${quantities.quantity == null ? "数量待补齐" : quantities.quantity + " 件"}，以分类明细为准。`,
          "确认更新采购数量",
          {
            confirmButtonText: "按明细保存",
            cancelButtonText: "返回修改",
            type: "warning",
          },
        );
      } catch {
        return;
      }
    }
    const data = await api(
      "/trade/requirements" + (id.value ? "/" + id.value : assistantDraft.value ? "/from-assistant/" + encodeURIComponent(route.params.session) + "/" + encodeURIComponent(route.params.turn) : ""),
      {
        scope: "buyer",
        method: id.value ? "PUT" : "POST",
        body: {
          title: form.title.trim(),
          rawText: form.rawText.trim(),
          structured,
          ...(id.value ? { expectedRevision: item.value.revision } : {}),
        },
      },
    );
    saved.value = submitted;
    item.value = data;
    ElMessage.success("采购需求已保存");
    if (!id.value) {
      bypass = true;
      await router.replace("/requirements/" + data.id);
    } else {
      await loadCheck();
    }
  } catch (e) {
    error.value = e.message;
    conflict.value = e.code === 409;
  } finally {
    busy.value = false;
  }
}
async function reload() {
  if (reloading || loading.value || busy.value) return;
  reloading = true;
  try {
    if (dirty.value) {
      try {
        await ElMessageBox.confirm(
          "读取最新需求会替换未保存的输入。",
          "读取最新版本",
          {
            confirmButtonText: "重新读取",
            cancelButtonText: "保留输入",
            type: "warning",
          },
        );
      } catch {
        return;
      }
    }
    await load();
    await loadRuns();
    await loadCheck();
  } finally {
    reloading = false;
  }
}
async function changeStatus() {
  if (dirty.value) {
    error.value = "请先保存当前修改，再更改需求状态";
    return;
  }
  const next = item.value.status === "OPEN" ? "CLOSED" : "OPEN";
  try {
    await ElMessageBox.confirm(
      next === "CLOSED"
        ? "关闭后暂时不能修改或整理需求，之后可以重新开启。"
        : "重新开启后可继续编辑采购需求。",
      next === "CLOSED" ? "关闭这份需求？" : "重新开启需求？",
      { confirmButtonText: "确认", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    item.value = await api("/trade/requirements/" + id.value + "/status", {
      scope: "buyer",
      method: "PUT",
      body: { status: next, expectedRevision: item.value.revision },
    });
    ElMessage.success(next === "CLOSED" ? "需求已关闭" : "需求已重新开启");
    await loadCheck();
  } catch (e) {
    error.value = e.message;
    conflict.value = e.code === 409;
  } finally {
    busy.value = false;
  }
}
async function analyze(kind = "REQUIREMENT") {
  if (dirty.value) {
    error.value = "请先保存修改，再整理需求";
    return;
  }
  busy.value = true;
  try {
    await api("/ai/runs", {
      scope: "buyer",
      method: "POST",
      body: {
        requirementId: id.value,
        expectedRevision: item.value.revision,
        kind,
      },
    });
    await loadRuns();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function cancel(run) {
  try {
    await api("/ai/runs/" + run.id + "/cancel", {
      scope: "buyer",
      method: "POST",
    });
    await loadRuns();
  } catch (e) {
    runError.value = e.message;
  }
}
async function adopt(run) {
  if (
    merchant.value ||
    busy.value ||
    loading.value ||
    reloading ||
    dirty.value ||
    !alive
  )
    return;
  const reason = suggestionBlockReason(run, item.value);
  if (reason) {
    runError.value = reason;
    return;
  }
  const capturedId = String(id.value),
    capturedRevision = String(item.value.revision),
    capturedForm = snapshot();
  const unchanged = () =>
    alive &&
    String(id.value) === capturedId &&
    String(item.value?.revision) === capturedRevision &&
    snapshot() === capturedForm;
  busy.value = true;
  runError.value = "";
  try {
    const proposed = suggestionForm(run, item.value);
    try {
      await ElMessageBox.confirm(
        "请先核对上方预算、设备数量和条件。确认后仅填入编辑表单，原需求文字保持不变；还需点击保存才会更新需求。",
        "核对并填入建议",
        { confirmButtonText: "已核对，填入表单", cancelButtonText: "暂不采用" },
      );
    } catch {
      return;
    }
    if (!unchanged()) return;
    const latest = await api("/trade/requirements/" + capturedId, {
      scope: "buyer",
    });
    if (!unchanged()) return;
    const latestReason = suggestionBlockReason(run, latest);
    if (latestReason) throw new Error(latestReason + "，请读取最新需求");
    Object.assign(form, proposed);
    ElMessage.success("建议已填入，请继续核对并保存采购需求");
  } catch (e) {
    if (alive) runError.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function leave() {
  if (bypass || !dirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "当前需求有未保存的输入，离开后将丢失。",
      "离开需求编辑？",
      {
        confirmButtonText: "离开",
        cancelButtonText: "继续编辑",
        type: "warning",
      },
    );
    return true;
  } catch {
    return false;
  }
}
function unload(e) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onBeforeRouteLeave(leave);
onMounted(async () => {
  await load();
  loadCheck();
  if (!merchant.value) {
    capabilities();
    loadRuns();
  }
  if (alive) window.addEventListener("beforeunload", unload);
});
onBeforeUnmount(() => {
  alive = false;
  clearTimeout(poll);
  window.removeEventListener("beforeunload", unload);
});
</script>
<template>
  <div class="page-container requirement-detail">
    <RouterLink
      v-if="!merchant && id"
      :to="'/requirements/' + id + '/plans'"
      class="back-link"
      >查看与比较商家方案 →</RouterLink
    >
    <RouterLink
      v-if="merchant && id"
      :to="'/merchant/requirements/' + id + '/plans'"
      class="back-link"
      >编制与查看采购方案 →</RouterLink
    >
    <RouterLink :to="prefix" class="back-link"
      ><Icon name="back" :size="17" />返回{{
        merchant ? "客户需求" : "我的需求"
      }}</RouterLink
    >
    <section class="page-heading">
      <div>
        <div class="eyebrow">复杂采购方案</div>
        <h1>
          {{
            id
              ? merchant
                ? "客户采购需求"
                : "补充采购需求"
              : "写下你的采购需求"
          }}
        </h1>
        <p class="muted">
          {{
            merchant
              ? "查看最新采购条件，编制方案并逐项核实资料依据。"
              : "适合需要商家协助搭配的采购。先描述需求，不确定的条件可以留空。"
          }}
        </p>
      </div>
      <div class="heading-actions">
        <span v-if="dirty" class="unsaved-mark">有未保存的输入</span
        ><el-button v-if="id" :disabled="busy || loading" @click="reload"
          >读取最新需求</el-button
        ><span v-if="item" class="status-pill"
          >{{ statusText }} · 第 {{ item.revision }} 版</span
        >
      </div>
    </section>
    <section v-if="assistantDraft && !id" class="panel" style="padding: 20px; margin-bottom: 24px">
      <h2>核对助手整理的采购需求</h2>
      <p>已带入你在对话中说明的条件。请核对预算、数量及待核实事项；点击保存后，商家才会看到这份需求。</p>
      <details v-if="assistantDraft.sourceMessages?.length">
        <summary>对照你在这段对话中说过的内容</summary>
        <p v-for="(text, index) in assistantDraft.sourceMessages" :key="index" style="white-space: pre-wrap">{{ text }}</p>
      </details>
    </section>
    <div v-if="error" class="editor-error">
      <el-alert
        :title="error"
        :type="conflict ? 'warning' : 'error'"
        :closable="false"
        show-icon
      />
      <p v-if="conflict">当前输入已保留。请核对最新需求后，再决定如何修改。</p>
    </div>
    <el-skeleton v-if="loading" animated :rows="10" />
    <div v-else-if="!id || item" class="requirement-edit-layout">
      <section class="panel requirement-form">
        <template v-if="merchant"
          ><h2>{{ item.title }}</h2>
          <p class="requirement-raw">{{ item.rawText }}</p>
          <dl class="requirement-summary">
            <div>
              <dt>总预算</dt>
              <dd>
                {{
                  item.structured.budgetCents
                    ? "¥ " + money(item.structured.budgetCents)
                    : "待确认"
                }}
              </dd>
            </div>
            <div>
              <dt>
                {{ legacyTotal ? "旧记录总件数（待分配）" : "采购总件数" }}
              </dt>
              <dd>{{ item.structured.quantity ?? "待确认" }}</dd>
            </div>
            <div>
              <dt>设备分类</dt>
              <dd>
                {{
                  item.structured.categoryIds
                    .map((c) => categoryOf(c).name)
                    .join("、") || "待确认"
                }}
              </dd>
            </div>
            <div>
              <dt>使用场景</dt>
              <dd>{{ item.structured.usage || "待补充" }}</dd>
            </div>
          </dl>
          <h3>分类采购明细</h3>
          <div v-if="form.items.length" class="requirement-item-list">
            <div
              v-for="line in form.items"
              :key="line.categoryId"
              class="requirement-item-row"
            >
              <span>{{ categoryOf(line.categoryId).name }}</span
              ><strong>{{
                line.quantity == null ? "数量待确认" : line.quantity + " 件"
              }}</strong>
            </div>
          </div>
          <p v-else class="muted">设备分类尚未填写。</p>
          <h3>必须满足</h3>
          <ul>
            <li v-for="s in item.structured.mustHave" :key="s">{{ s }}</li>
            <li v-if="!item.structured.mustHave.length" class="muted">
              尚未填写
            </li>
          </ul>
          <h3>优先考虑</h3>
          <ul>
            <li v-for="s in item.structured.optional" :key="s">{{ s }}</li>
            <li v-if="!item.structured.optional.length" class="muted">
              尚未填写
            </li>
          </ul></template
        >
        <el-form
          v-else
          label-position="top"
          :disabled="busy || (item && item.status !== 'OPEN')"
          @submit.prevent="save"
          ><el-form-item label="需求标题" required
            ><el-input
              v-model="form.title"
              maxlength="100"
              aria-label="需求标题"
              placeholder="例如：远程办公团队的键鼠与耳机采购" /></el-form-item
          ><el-form-item label="采购需求" required
            ><el-input
              v-model="form.rawText"
              type="textarea"
              :rows="5"
              maxlength="4000"
              show-word-limit
              aria-label="采购需求"
              placeholder="说明给谁使用、需要哪些设备、使用场景，以及必须满足的条件。"
          /></el-form-item>
          <div class="subsection-heading">
            <div>
              <h3>明确采购条件</h3>
              <p class="muted">
                手工填写已确认的信息；预算为本次全部设备的总预算。
              </p>
            </div>
          </div>
          <div class="form-grid">
            <el-form-item label="总预算（元，可留空）"
              ><el-input
                v-model="form.budget"
                inputmode="decimal"
                aria-label="采购总预算"
                placeholder="尚未确定"
                ><template #prefix>¥</template></el-input
              ></el-form-item
            ><el-form-item label="采购总件数（按明细计算）"
              ><div class="requirement-total">
                {{
                  totalQuantity == null
                    ? "待补齐各类数量"
                    : totalQuantity + " 件设备"
                }}
              </div></el-form-item
            >
          </div>
          <el-form-item label="需要的设备分类"
            ><el-checkbox-group v-model="selectedCategories"
              ><el-checkbox
                v-for="category in categories"
                :key="category.id"
                :value="category.id"
                >{{ category.name }}</el-checkbox
              ></el-checkbox-group
            ></el-form-item
          >
          <el-alert
            v-if="legacyTotal"
            :title="`旧记录只填写了总共 ${legacyTotal} 件，尚未分配到各类设备。仅修改其他信息会保留原总数；调整分类数量后再按明细计算。`"
            type="info"
            :closable="false"
            class="legacy-quantity"
          />
          <div v-if="form.items.length" class="requirement-item-list">
            <el-form-item
              v-for="line in form.items"
              :key="line.categoryId"
              :label="categoryOf(line.categoryId).name + '（件，可留空）'"
              ><el-input
                v-model="line.quantity"
                inputmode="numeric"
                maxlength="5"
                :aria-label="categoryOf(line.categoryId).name + '数量'"
                placeholder="尚未确定"
            /></el-form-item>
          </div>
          <p class="muted fine-print">
            填写设备件数。例如2人各需一套键鼠，应分别填2个键盘和2个鼠标。
          </p>
          <el-form-item label="主要使用场景"
            ><el-input
              v-model="form.usage"
              maxlength="1000"
              aria-label="使用场景"
              placeholder="例如：日常文档处理、线上会议、开放式办公室"
          /></el-form-item>
          <div class="form-grid">
            <el-form-item label="必须满足（每行一项）"
              ><el-input
                v-model="form.mustHave"
                type="textarea"
                :rows="4"
                aria-label="必须满足"
                placeholder="例如：耳机需要麦克风" /></el-form-item
            ><el-form-item label="优先考虑（每行一项）"
              ><el-input
                v-model="form.optional"
                type="textarea"
                :rows="4"
                aria-label="优先考虑"
                placeholder="例如：键盘尽量安静"
            /></el-form-item>
          </div>
          <div class="section-actions">
            <span class="muted">保存后商家可查看这份需求。</span
            ><el-button type="primary" native-type="submit" :loading="busy">{{
              id ? "保存采购需求" : "保存并继续"
            }}</el-button>
          </div></el-form
        >
      </section>
      <aside class="requirement-side">
        <section class="panel requirement-check">
          <div class="section-title">
            <h2>采购条件检查</h2>
            <el-button
              v-if="id"
              text
              size="small"
              :disabled="checking"
              @click="loadCheck"
              >刷新检查</el-button
            >
          </div>
          <p v-if="!id" class="muted">保存需求后，查看还需要补充哪些条件。</p>
          <template v-else>
            <el-alert
              v-if="checkError"
              :title="checkError"
              type="warning"
              :closable="false"
            />
            <p v-if="checking" class="muted">正在检查已保存的条件…</p>
            <template
              v-else-if="
                check && String(check.revision) === String(item.revision)
              "
            >
              <span class="status-pill"
                >规则检查 · 第 {{ check.revision }} 版</span
              >
              <p v-if="dirty" class="check-draft">
                你有未保存的修改。下面仍按已保存的版本检查。
              </p>
              <p v-if="check.complete" class="check-complete">
                基础条件已填写完整
              </p>
              <ul v-else>
                <li
                  v-for="gap in check.missing"
                  :key="gap.code + ':' + gap.categoryId"
                >
                  {{ gap.message }}
                </li>
              </ul>
            </template>
            <p v-else-if="check" class="check-draft">
              需求版本已变化，请读取最新需求后再核对。
            </p>
          </template>
          <p class="fine-print muted">
            检查预算、数量和用途是否齐全。具体设备是否合适，还需在方案中核对。
          </p>
        </section>
        <details class="panel requirement-guide">
          <summary>怎样写清采购需求</summary>
          <ol>
            <li>
              <strong>使用场景</strong>
              <p>给谁用、在哪里用、主要完成什么工作。</p>
            </li>
            <li>
              <strong>预算与数量</strong>
              <p>写清总预算和设备件数，不确定的先留空。</p>
            </li>
            <li>
              <strong>条件的优先级</strong>
              <p>区分必须满足与优先考虑，减少选购时的取舍困难。</p>
            </li>
          </ol>
        </details>
        <section v-if="!merchant" class="panel run-panel">
          <div class="section-title">
            <h2>AI辅助整理</h2>
            <Icon name="document" :size="21" />
          </div>
          <p class="muted">{{ capability.message }}</p>
          <el-button
            class="full-width"
            type="primary"
            :disabled="
              !id ||
              !capability.available ||
              dirty ||
              busy ||
              item?.status !== 'OPEN' ||
              runs.some((r) => r.status === 'RUNNING')
            "
            @click="analyze()"
            >请AI整理需求</el-button
          >
          <el-button
            :disabled="
              !capability.available ||
              !id ||
              dirty ||
              busy ||
              loading ||
              item?.status !== 'OPEN' ||
              runs.some((r) => r.status === 'RUNNING')
            "
            @click="analyze('CANDIDATE')"
            >查找参考商品</el-button
          >
          <p class="fine-print muted">
            请先保存修改。AI会根据已保存的条件整理建议、查找参考商品；你确认条件后，由商家编制方案。
          </p>
          <el-alert
            v-if="runError"
            :title="runError"
            type="warning"
            :closable="false"
          />
          <div v-if="id" class="run-history">
            <div class="section-title">
              <h3>整理记录</h3>
              <el-button text size="small" @click="loadRuns">刷新</el-button>
            </div>
            <p v-if="!runs.length" class="muted fine-print">还没有整理记录。</p>
            <details v-for="(run, index) in runs" :key="run.id" class="run-card" :open="index === 0 || run.status === 'RUNNING'">
              <summary>
                <strong>{{ states[run.status] }}</strong
                ><small
                  >需求第 {{ run.requirementRevision }} 版{{
                    String(run.requirementRevision) !== String(item.revision)
                      ? " · 旧版本"
                      : ""
                  }}</small
                >
              </summary>
              <p v-if="run.errorMessage">{{ run.errorMessage }}</p>
              <p v-else-if="run.status === 'FAILED'">
                {{ run.errorCode === 'MODEL_CATEGORY_QUESTION'
                  ? 'AI对设备类型的判断前后矛盾，这次结果未采用。你的需求仍然保留，可以手动补充或重新整理。'
                  : '这次AI整理未完成，未修改你的需求。你可以继续手动填写，或稍后重新整理。' }}
              </p>
              <p v-if="run.result">
                这是AI的建议，尚未修改你的需求。
              </p>
              <CandidateReference
                v-if="run.result?.candidate"
                :proposal="run.result.candidate"
              />
              <div v-else-if="run.result" class="analysis-conditions">
                <p><strong>AI建议条件 · 请人工核对</strong></p>
                <p>
                  预算：{{ money(run.result.conditions.budgetCents) }} ·
                  总件数：{{ run.result.conditions.quantity ?? "待确认" }}
                </p>
                <p>用途：{{ run.result.conditions.usage || "待确认" }}</p>
                <p>
                  必须满足：{{
                    run.result.conditions.mustHave.join("；") || "未明确"
                  }}
                </p>
                <p>
                  优先考虑：{{
                    run.result.conditions.optional.join("；") || "未明确"
                  }}
                </p>
                <ul v-if="run.result.conditions.items?.length">
                  <li
                    v-for="line in run.result.conditions.items"
                    :key="line.categoryId"
                  >
                    {{ categoryOf(line.categoryId).name }}：{{
                      line.quantity ?? "待确认"
                    }}
                    件
                  </li>
                </ul>
                <p class="fine-print muted">
                  仅根据需求描述整理；商品是否符合条件，还需核对资料。
                </p>
              </div>
              <ul v-if="run.result?.questions?.length">
                <li v-for="q in run.result.questions" :key="q">{{ q }}</li>
              </ul>
              <div
                v-if="run.result?.adjustments?.length"
                class="fine-print muted"
              >
                <strong>系统整理说明</strong>
                <ul>
                  <li v-for="note in run.result.adjustments" :key="note">
                    {{ note }}
                  </li>
                </ul>
              </div>
              <el-button
                v-if="run.result && !run.result.candidate"
                :disabled="
                  busy || loading || dirty || !!suggestionBlockReason(run, item)
                "
                @click="adopt(run)"
                >核对并填入表单</el-button
              >
              <p
                v-if="
                  run.result &&
                  !run.result.candidate &&
                  suggestionBlockReason(run, item)
                "
                class="fine-print muted"
              >
                {{ suggestionBlockReason(run, item) }}
              </p>
              <el-button
                v-if="run.status === 'RUNNING'"
                text
                type="danger"
                @click="cancel(run)"
                >取消整理</el-button
              >
              <details v-if="run.model || run.promptVersion" class="run-technical">
                <summary>技术记录</summary>
                <p>模型：{{ run.model || "未记录" }}</p>
                <p>整理规则：{{ run.promptVersion || "未记录" }}</p>
              </details>
            </details>
          </div>
        </section>
        <el-button
          v-if="id && !merchant && item.status !== 'ORDERED'"
          :disabled="busy"
          @click="changeStatus"
          >{{
            item.status === "OPEN" ? "关闭这份需求" : "重新开启需求"
          }}</el-button
        >
        <p v-if="id && !merchant && item.status !== 'ORDERED'" class="fine-print muted">
          暂不采购时可关闭需求，记录仍会保留，之后可以重新开启。
        </p>
      </aside>
    </div>
    <div v-else class="empty-state">
      <h2>无法读取需求</h2>
      <el-button @click="load">重试</el-button>
    </div>
  </div>
</template>
