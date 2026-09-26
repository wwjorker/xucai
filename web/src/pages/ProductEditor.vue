<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api";
import {
  categories,
  specLabels,
  statusName,
  money,
  toCents,
  safeUrl,
  productImageUrl,
} from "../domain";
import { indexLabel } from "../chunks.js";
import Icon from "../components/Icon.vue";
import MerchantProductImage from "../components/MerchantProductImage.vue";
const route = useRoute(),
  router = useRouter(),
  id = computed(() => route.params.id);
const product = ref(null),
  docs = ref([]),
  loading = ref(true),
  error = ref(""),
  conflict = ref(false),
  busy = ref(""),
  saleUnavailable = ref(false),
  docIndex = ref([]),
  indexError = ref("");
async function uploadImage(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  error.value = "";
  if (!id.value || dirty.value || busy.value || sale.onSale || saleUnavailable.value) {
    error.value = "请先保存当前编辑并停售，再上传商品图片";
    return;
  }
  if (file.size > 2 * 1024 * 1024 || !["image/jpeg", "image/png"].includes(file.type)) {
    error.value = "请选择不超过2MB的JPEG或PNG图片";
    return;
  }
  busy.value = "image";
  try {
    const body = new FormData();
    body.append("file", file);
    body.append("expectedSpecVersion", product.value.specVersion);
    const result = await api(`/catalog/admin/products/${id.value}/image`, { scope: "merchant", method: "POST", body, retry: false });
    form.imageUrl = result.imageUrl;
    product.value.specVersion = result.specVersion;
    product.value.imageUrl = result.imageUrl;
    basicsSaved.value = basicsSnapshot();
    ElMessage.success("商品图片已上传并保存");
  } catch (e) { fail(e); }
  finally { busy.value = ""; }
}
const form = reactive({
  name: "",
  brand: "",
  series: "",
  categoryId: 3,
  imageUrl: "",
  description: "",
});
const specs = ref([{ key: "", label: "连接方式", format: "text", value: "" }]);
const sale = reactive({ price: "", stock: 0, onSale: false }),
  saleVersion = ref("0"),
  expectedStock = ref(0);
const doc = reactive({ title: "", sourceUrl: "", content: "" }),
  docTarget = ref("new"),
  docVersion = ref(0);
const basicsSaved = ref(""),
  saleSaved = ref(""),
  docSaved = ref("");
let bypassLeave = false,
  selectedDocTarget = "new",
  pendingDocKey = null;
const basicsSnapshot = () => JSON.stringify([form, specs.value]);
const dirty = computed(
  () =>
    !loading.value &&
    (basicsSnapshot() !== basicsSaved.value ||
      JSON.stringify(sale) !== saleSaved.value ||
      JSON.stringify(doc) !== docSaved.value),
);
const docGroups = computed(() => {
  const map = new Map();
  for (const d of docs.value)
    if (
      !map.has(d.docKey) ||
      Number(map.get(d.docKey).version) < Number(d.version)
    )
      map.set(d.docKey, d);
  return [...map.values()];
});
const activeDocs = computed(
  () => docs.value.filter((d) => d.status === "ACTIVE").length,
);
function markSaved() {
  basicsSaved.value = basicsSnapshot();
  saleSaved.value = JSON.stringify(sale);
  docSaved.value = JSON.stringify(doc);
}
function fail(e) {
  error.value = e.message;
  conflict.value = e.code === 409;
}
async function load() {
  loading.value = true;
  error.value = "";
  conflict.value = false;
  if (!id.value) {
    markSaved();
    loading.value = false;
    return;
  }
  try {
    const [p, d, s] = await Promise.all([
      api("/catalog/admin/products/" + id.value, { scope: "merchant" }),
      api("/catalog/admin/products/" + id.value + "/docs", { scope: "merchant" }),
      api("/trade/admin/sale-items/" + id.value, { scope: "merchant" }).catch(
        (e) => {
          saleUnavailable.value = true;
          return null;
        },
      ),
    ]);
    product.value = p;
    docs.value = d;
    loadIndex();
    for (const key of Object.keys(form)) form[key] = p[key] ?? "";
    specs.value = Object.entries(p.specJson || {}).map(([key, value]) => {
      const format = Array.isArray(value)
        ? "list"
        : typeof value === "boolean"
          ? "boolean"
          : typeof value === "number"
            ? "number"
            : typeof value === "object"
              ? "json"
              : "text";
      const display = Array.isArray(value)
        ? value.join("、")
        : typeof value === "object"
          ? JSON.stringify(value)
          : value;
      return {
        key,
        label: specLabels[key] || key,
        format,
        value: display,
        original: value,
        originalDisplay: display,
        originalFormat: format,
      };
    });
    if (!saleUnavailable.value) {
      sale.price = s ? String(Number(s.price) / 100) : "";
      sale.stock = s?.stock ?? 0;
      sale.onSale = s?.onSale ?? false;
      saleVersion.value = s?.saleVersion ?? "0";
      expectedStock.value = s?.stock ?? 0;
    }
    docTarget.value = "new";
    selectedDocTarget = "new";
    pendingDocKey = null;
    Object.assign(doc, { title: "", sourceUrl: "", content: "" });
    docVersion.value = 0;
    markSaved();
  } catch (e) {
    fail(e);
  } finally {
    loading.value = false;
  }
}
async function refreshAll() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm(
        "重新读取会替换当前未保存的输入，是否继续？",
        "读取最新数据",
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
  saleUnavailable.value = false;
  await load();
}
function buildSpec() {
  const result = {};
  for (const row of specs.value) {
    const key = row.key || row.label.trim();
    if (!key) throw new Error("请填写每一项参数名称");
    if (Object.hasOwn(result, key)) throw new Error("参数名称不能重复");
    if (
      row.key &&
      row.format === row.originalFormat &&
      row.value === row.originalDisplay
    )
      result[key] = row.original;
    else if (row.format === "json") {
      try {
        result[key] = JSON.parse(row.value);
      } catch {
        throw new Error("结构化参数内容不是有效JSON");
      }
    } else if (row.format === "boolean") result[key] = row.value === true;
    else if (row.format === "number") {
      if (
        row.value === "" ||
        row.value == null ||
        !Number.isFinite(Number(row.value))
      )
        throw new Error("请输入有效参数数值");
      result[key] = Number(row.value);
    } else {
      if (!String(row.value ?? "").trim())
        throw new Error("请填写每一项参数值");
      result[key] =
        row.format === "list"
          ? String(row.value)
              .split("、")
              .map((x) => x.trim())
              .filter(Boolean)
          : String(row.value).trim();
    }
  }
  if (!Object.keys(result).length) throw new Error("请至少填写一项商品规格");
  return result;
}
async function saveBasics() {
  error.value = "";
  conflict.value = false;
  try {
    if (
      !form.name.trim() ||
      !form.brand.trim() ||
      !form.series.trim() ||
      !form.description.trim()
    )
      throw new Error("请完整填写名称、品牌、系列和商品说明");
    if (form.imageUrl && !productImageUrl(form.imageUrl))
      throw new Error("请输入有效的HTTP或HTTPS图片地址");
    const payload = {
      ...form,
      specJson: buildSpec(),
      ...(id.value ? { expectedSpecVersion: product.value.specVersion } : {}),
    };
    const submitted = basicsSnapshot();
    busy.value = "basics";
    const result = await api(
      "/catalog/admin/products" + (id.value ? "/" + id.value : ""),
      { scope: "merchant", method: id.value ? "PUT" : "POST", body: payload },
    );
    basicsSaved.value = submitted;
    ElMessage.success(
      id.value ? "商品档案已保存" : "商品档案已建立，请添加来源资料",
    );
    if (!id.value) {
      bypassLeave = true;
      await router.replace("/merchant/products/" + result.id + "/edit");
    } else {
      product.value = { ...product.value, ...result, name: payload.name };
    }
  } catch (e) {
    fail(e);
  } finally {
    busy.value = "";
  }
}
function chooseDoc() {
  const selected = docGroups.value.find((d) => d.docKey === docTarget.value);
  Object.assign(
    doc,
    selected
      ? {
          title: selected.title,
          sourceUrl: selected.sourceUrl,
          content: selected.content,
        }
      : { title: "", sourceUrl: "", content: "" },
  );
  docVersion.value = selected?.version ?? 0;
  docSaved.value = JSON.stringify(doc);
  selectedDocTarget = docTarget.value;
  pendingDocKey = null;
}
async function switchDoc(target) {
  if (busy.value) return;
  if (JSON.stringify(doc) !== docSaved.value) {
    try {
      await ElMessageBox.confirm(
        "切换将放弃当前资料尚未保存的输入。",
        "切换资料？",
        {
          confirmButtonText: "放弃并切换",
          cancelButtonText: "继续编辑",
          type: "warning",
        },
      );
    } catch {
      docTarget.value = selectedDocTarget;
      return;
    }
  }
  docTarget.value = target;
  chooseDoc();
}
async function saveDoc() {
  error.value = "";
  conflict.value = false;
  try {
    if (!doc.title.trim() || !doc.content.trim() || !safeUrl(doc.sourceUrl))
      throw new Error("请填写资料标题、有效来源链接和正文");
    busy.value = "doc";
    const key =
      docTarget.value === "new"
        ? (pendingDocKey ??= "source-" + crypto.randomUUID())
        : docTarget.value;
    const submitted = JSON.stringify(doc);
    const saved = await api("/catalog/admin/products/" + id.value + "/docs", {
      scope: "merchant",
      method: "POST",
      body: { ...doc, docKey: key, expectedVersion: docVersion.value },
    });
    docTarget.value = key;
    selectedDocTarget = key;
    docVersion.value = saved.version;
    docSaved.value = submitted;
    pendingDocKey = null;
    docs.value = [
      ...docs.value.map((d) =>
        d.docKey === key && d.status === "ACTIVE"
          ? { ...d, status: "SUPERSEDED" }
          : d,
      ),
      {
        ...JSON.parse(submitted),
        ...saved,
        id: "pending-" + key,
        docKey: key,
        status: "ACTIVE",
        pending: true,
      },
    ];
    ElMessage.success("新资料版本已保存，历史正文保留");
    try {
      docs.value = await api("/catalog/admin/products/" + id.value + "/docs", {
        scope: "merchant",
      });
    } catch {
      error.value =
        "资料版本已保存，但历史列表加载失败。请重新读取最新数据后再退役资料。";
    }
    loadIndex();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = "";
  }
}
// 片段是资料正文的派生物，单独读取；读不到只影响片段区，不影响资料编辑。
async function loadIndex() {
  if (!id.value) return;
  indexError.value = "";
  try {
    docIndex.value = await api("/catalog/admin/products/" + id.value + "/doc-index", {
      scope: "merchant",
    });
  } catch (e) {
    docIndex.value = [];
    indexError.value = "片段状态读取失败：" + e.message;
  }
}
const indexOf = (d) =>
  docIndex.value.find((x) => String(x.docId) === String(d.id)) || null;
async function reindex(d) {
  indexError.value = "";
  busy.value = "reindex";
  try {
    const state = await api(
      "/catalog/admin/products/" + id.value + "/docs/" + d.id + "/reindex",
      { scope: "merchant", method: "POST" },
    );
    docIndex.value = [
      ...docIndex.value.filter((x) => String(x.docId) !== String(d.id)),
      state,
    ];
    ElMessage.success(
      state.indexState === "READY"
        ? `片段已重建：共 ${state.chunkCount} 段，正文未改动`
        : "片段重建结束，但状态不是可用，请查看说明",
    );
  } catch (e) {
    indexError.value = "重建失败：" + e.message;
  } finally {
    busy.value = "";
  }
}
async function retireDoc(d) {
  try {
    await ElMessageBox.confirm(
      "退役后不再作为当前资料显示，历史正文仍保留。",
      "退役这份资料？",
      {
        confirmButtonText: "确认退役",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
  } catch {
    return;
  }
  error.value = "";
  busy.value = "retire";
  try {
    await api("/catalog/admin/products/" + id.value + "/docs/" + d.id + "/retire", {
      scope: "merchant",
      method: "PUT",
    });
    docs.value = await api("/catalog/admin/products/" + id.value + "/docs", {
      scope: "merchant",
    });
    ElMessage.success("资料已退役");
    loadIndex();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = "";
  }
}
async function saveSale() {
  error.value = "";
  conflict.value = false;
  try {
    const price = toCents(sale.price);
    if (!Number.isInteger(sale.stock) || sale.stock < 0 || sale.stock > 1000000)
      throw new Error("库存须为0至1,000,000的整数");
    const submitted = JSON.stringify(sale);
    busy.value = "sale";
    const result = await api("/trade/admin/sale-items/" + id.value, {
      scope: "merchant",
      method: "PUT",
      body: {
        price,
        stock: sale.stock,
        onSale: sale.onSale,
        expectedSaleVersion: saleVersion.value,
        expectedStock: expectedStock.value,
      },
    });
    saleVersion.value = result.saleVersion;
    expectedStock.value = result.stock;
    saleSaved.value = submitted;
    ElMessage.success("售卖设置已保存");
  } catch (e) {
    fail(e);
  } finally {
    busy.value = "";
  }
}
async function changeStatus(status) {
  if (basicsSnapshot() !== basicsSaved.value) {
    error.value = "商品档案有未保存的修改，请先保存后再更改发布状态";
    return;
  }
  if (
    status === "HIDDEN" &&
    (sale.onSale || JSON.stringify(sale) !== saleSaved.value)
  ) {
    error.value = "请先关闭开售并保存售卖设置，再隐藏商品";
    return;
  }
  error.value = "";
  conflict.value = false;
  busy.value = "status";
  try {
    const updated = await api("/catalog/admin/products/" + id.value + "/status", {
      scope: "merchant",
      method: "PUT",
      body: { status, expectedSpecVersion: product.value.specVersion },
    });
    product.value = { ...product.value, ...updated };
    ElMessage.success(
      status === "PUBLISHED" ? "商品已发布，请核对售卖设置" : "商品已隐藏",
    );
  } catch (e) {
    fail(e);
  } finally {
    busy.value = "";
  }
}
async function canLeave() {
  if (bypassLeave || !dirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "当前仍有未保存的输入，离开后将丢失。",
      "离开编辑页？",
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
function beforeUnload(event) {
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onBeforeRouteLeave(canLeave);
onMounted(() => {
  load();
  window.addEventListener("beforeunload", beforeUnload);
});
onBeforeUnmount(() => window.removeEventListener("beforeunload", beforeUnload));
</script>
<template>
  <p class="page-container">
    更正规格、更换或退役资料前，请先在售卖设置中停售；受影响的方案需要重新编制与核实。
  </p>
  <div class="page-container editor-page">
    <RouterLink to="/merchant/products" class="back-link"
      ><Icon name="back" :size="17" /> 返回商品管理</RouterLink
    >
    <section class="page-heading">
      <div>
        <div class="eyebrow">PRODUCT WORKSPACE</div>
        <h1>{{ id ? "管理商品" : "建立一份商品档案" }}</h1>
        <p class="muted">
          {{
            id ? product?.name : "先保存基本信息，再添加来源资料与售卖设置。"
          }}
        </p>
      </div>
      <div class="heading-actions">
        <span v-if="dirty" class="unsaved-mark">有未保存的输入</span
        ><el-button v-if="id" @click="refreshAll" :disabled="!!busy"
          >重新读取最新数据</el-button
        ><RouterLink
          v-if="id && product?.status !== 'DRAFT'"
          :to="'/products/' + id"
          class="button-link secondary"
          >查看客户详情 <Icon name="link" :size="16"
        /></RouterLink>
      </div>
    </section>
    <div v-if="error" class="editor-error" role="alert">
      <el-alert
        :title="error"
        :type="conflict ? 'warning' : 'error'"
        :closable="false"
        show-icon
      />
      <p v-if="conflict">
        其他操作已更新了数据。当前输入未覆盖，请先记录需要保留的内容，再重新读取最新数据。
      </p>
    </div>
    <el-skeleton v-if="loading" animated :rows="10" />
    <div v-else-if="!id || product" class="editor-grid">
      <div class="editor-main">
        <section class="panel editor-section">
          <div class="section-title">
            <div>
              <span class="eyebrow">01 / PRODUCT PROFILE</span>
              <h2>商品基本信息</h2>
            </div>
            <span v-if="product" class="muted"
              >规格版本 {{ product.specVersion }}</span
            >
          </div>
          <el-form
            :disabled="!!busy"
            label-position="top"
            @submit.prevent="saveBasics"
          >
            <el-form-item label="商品名称" required
              ><el-input
                v-model="form.name"
                maxlength="160"
                placeholder="例如：罗技 M185 无线鼠标（MR0102）"
                aria-label="商品名称"
            /></el-form-item>
            <div class="form-grid">
              <el-form-item label="品牌" required
                ><el-input
                  v-model="form.brand"
                  maxlength="80"
                  placeholder="例如：罗技"
                  aria-label="品牌" /></el-form-item
              ><el-form-item label="系列 / 型号" required
                ><el-input
                  v-model="form.series"
                  maxlength="120"
                  placeholder="请写清型号与具体版本"
                  aria-label="系列型号"
              /></el-form-item>
            </div>
            <div class="form-grid">
              <el-form-item label="设备分类" required
                ><el-select v-model="form.categoryId" aria-label="设备分类"
                  ><el-option
                    v-for="c in categories"
                    :key="c.id"
                    :label="c.name"
                    :value="c.id" /></el-select></el-form-item
              ><el-form-item label="商品图片链接（可选）"
                ><el-input
                  v-if="!form.imageUrl.startsWith('/api/catalog/products/')"
                  v-model="form.imageUrl"
                  maxlength="1000"
                  placeholder="https://..."
                  aria-label="商品图片链接"
              /><span v-else>已保存商品图片，可在下方上传替换</span></el-form-item>
            </div>
            <el-form-item label="上传商品图片">
              <input type="file" accept="image/jpeg,image/png" aria-label="上传商品图片"
                :disabled="!id || !!busy || dirty || sale.onSale || saleUnavailable" @change="uploadImage" />
              <p class="muted">先保存商品并停售，再上传不超过2MB的JPEG或PNG。上传成功立即保存；请仅使用有权使用且型号一致的图片。</p>
              <MerchantProductImage :image-url="product?.imageUrl" />
            </el-form-item>
            <el-form-item label="商品说明" required
              ><el-input
                v-model="form.description"
                type="textarea"
                :rows="3"
                maxlength="4000"
                show-word-limit
                placeholder="描述用途、版本限定与需要采购者注意的事项"
                aria-label="商品说明"
            /></el-form-item>
            <div class="subsection-heading">
              <div>
                <h3>设备规格</h3>
                <p class="muted">
                  请依据资料填写参数。已有参数名称保留，可删除后新增。
                </p>
              </div>
              <el-button
                size="small"
                :disabled="!!busy"
                @click="
                  specs.push({ key: '', label: '', format: 'text', value: '' })
                "
                ><Icon name="plus" :size="15" /> 添加参数</el-button
              >
            </div>
            <div
              v-for="(row, index) in specs"
              :key="index"
              class="spec-edit-row"
            >
              <el-input
                v-model="row.label"
                :disabled="!!row.key"
                placeholder="参数名称"
                :aria-label="'参数名称' + (index + 1)"
              /><el-select
                v-model="row.format"
                :aria-label="'参数形式' + (index + 1)"
                @change="row.value = row.format === 'boolean' ? false : ''"
                ><el-option label="文字说明" value="text" /><el-option
                  v-if="row.originalFormat === 'json'"
                  label="结构化数据"
                  value="json" /><el-option
                  label="数值"
                  value="number" /><el-option
                  label="是否支持"
                  value="boolean" /><el-option
                  label="多个选项"
                  value="list" /></el-select
              ><el-select
                v-if="row.format === 'boolean'"
                v-model="row.value"
                :aria-label="'参数值' + (index + 1)"
                ><el-option label="支持" :value="true" /><el-option
                  label="不支持"
                  :value="false" /></el-select
              ><el-input
                v-else
                v-model="row.value"
                :placeholder="
                  row.format === 'list' ? '用顿号分隔多个选项' : '参数值'
                "
                :aria-label="'参数值' + (index + 1)"
              /><button
                class="icon-button remove-spec"
                :disabled="!!busy"
                type="button"
                :aria-label="'删除参数' + (index + 1)"
                @click="specs.splice(index, 1)"
              >
                ×
              </button>
            </div>
            <div class="section-actions">
              <span class="muted">每次保存会更新规格版本。</span
              ><el-button
                type="primary"
                native-type="submit"
                :loading="busy === 'basics'"
                :disabled="!!busy && busy !== 'basics'"
                >{{ id ? "保存商品档案" : "保存并继续" }}</el-button
              >
            </div>
          </el-form>
        </section>
        <section
          class="panel editor-section"
          :class="{ 'locked-section': !id }"
        >
          <div class="section-title">
            <div>
              <span class="eyebrow">02 / SOURCE MATERIALS</span>
              <h2>资料与版本</h2>
            </div>
            <span class="muted">{{ activeDocs }} 份有效资料</span>
          </div>
          <p v-if="!id" class="empty-inline">
            保存商品档案后，即可添加来源资料。
          </p>
          <template v-else>
            <p class="muted section-description">
              发布前至少添加一份有效资料。修改内容将保存为新版本，旧正文会保留。
            </p>
            <el-form
              :disabled="!!busy"
              label-position="top"
              @submit.prevent="saveDoc"
              ><el-form-item label="资料操作"
                ><el-select
                  :model-value="docTarget"
                  aria-label="资料操作"
                  @change="switchDoc"
                  ><el-option label="添加一份新资料" value="new" /><el-option
                    v-for="d in docGroups"
                    :key="d.docKey"
                    :label="'更新：' + d.title"
                    :value="d.docKey" /></el-select
              ></el-form-item>
              <div class="form-grid">
                <el-form-item label="资料标题" required
                  ><el-input
                    v-model="doc.title"
                    maxlength="200"
                    aria-label="资料标题"
                    placeholder="例如：M185 官方规格摘记" /></el-form-item
                ><el-form-item label="原始来源链接" required
                  ><el-input
                    v-model="doc.sourceUrl"
                    maxlength="1000"
                    aria-label="原始来源链接"
                    placeholder="https://..."
                /></el-form-item>
              </div>
              <el-form-item label="资料正文" required
                ><el-input
                  v-model="doc.content"
                  type="textarea"
                  :rows="7"
                  maxlength="30000"
                  show-word-limit
                  aria-label="资料正文"
                  placeholder="保存整理后的规格、说明与版本限定。可输入Markdown文本。"
              /></el-form-item>
              <div class="section-actions">
                <span class="muted">{{
                  docTarget === "new"
                    ? "将建立第一版资料"
                    : "将基于版本 " + docVersion + " 保存新版本"
                }}</span
                ><el-button
                  native-type="submit"
                  :loading="busy === 'doc'"
                  :disabled="!!busy && busy !== 'doc'"
                  >保存资料版本</el-button
                >
              </div></el-form
            >
            <div class="doc-history">
              <h3>资料历史与片段</h3>
              <p class="muted chunk-hint">
                片段由正文自动切分，只在句末或段落边界切开，用于按关键词定位原文。重建只重算片段，不修改正文，也不影响已发布方案里的引用。
              </p>
              <el-alert
                v-if="indexError"
                :title="indexError"
                type="warning"
                :closable="false"
                show-icon
                class="panel-alert"
              />
              <div v-if="!docs.length" class="empty-inline">尚未添加资料。</div>
              <div class="doc-history-row" v-for="d in docs" :key="d.id">
                <Icon name="document" :size="20" />
                <div>
                  <strong>{{ d.title }}</strong
                  ><small
                    >第 {{ d.version }} 版 · {{ statusName(d.status) }}
                    <template v-if="!d.pending && indexOf(d)"
                      >· 片段{{ indexOf(d).chunkCount }}段 ·
                      {{ indexLabel(indexOf(d).indexState).label }}</template
                    ><template v-else-if="!d.pending">· 片段状态未知</template>
                  </small>
                  <small
                    v-if="!d.pending && indexOf(d)?.reason"
                    class="chunk-reason"
                    >{{ indexOf(d).reason }}</small
                  >
                </div>
                <el-button
                  v-if="!d.pending"
                  size="small"
                  text
                  :disabled="!!busy"
                  :loading="busy === 'reindex'"
                  @click="reindex(d)"
                  >重建片段</el-button
                >
                <el-button
                  v-if="!d.pending && d.status === 'ACTIVE'"
                  size="small"
                  text
                  type="danger"
                  :disabled="!!busy"
                  @click="retireDoc(d)"
                  >退役</el-button
                >
              </div>
              <RouterLink
                v-if="id && docs.length"
                class="text-link chunk-link"
                :to="`/merchant/doc-chunks?productId=${id}&includeUnpublished=true`"
                >在资料片段中查找这款商品的原文 →</RouterLink
              >
            </div>
          </template>
        </section>
      </div>
      <aside class="editor-side">
        <section class="panel status-panel">
          <div class="section-title">
            <h2>发布状态</h2>
            <span
              :class="[
                'status-pill',
                (product?.status || 'DRAFT').toLowerCase(),
              ]"
              >{{ statusName(product?.status || "DRAFT") }}</span
            >
          </div>
          <p class="muted">
            {{
              !id
                ? "保存商品后继续发布流程。"
                : product?.status === "DRAFT"
                  ? "档案仅商家可见。添加资料后可发布到客户目录。"
                  : product?.status === "HIDDEN"
                    ? "商品已从目录隐藏。核对资料后可重新发布。"
                    : "客户可查看商品档案。是否可购买由售卖设置决定。"
            }}
          </p>
          <el-button
            v-if="id && product?.status !== 'PUBLISHED'"
            type="primary"
            class="full-width"
            :disabled="!!busy || !activeDocs"
            :loading="busy === 'status'"
            @click="changeStatus('PUBLISHED')"
            >发布到客户目录</el-button
          ><el-button
            v-if="id && product?.status === 'PUBLISHED'"
            class="full-width"
            :disabled="!!busy"
            :loading="busy === 'status'"
            @click="changeStatus('HIDDEN')"
            >隐藏商品</el-button
          >
        </section>
        <section class="panel sale-panel">
          <div class="section-title">
            <div>
              <span class="eyebrow">03 / AVAILABILITY</span>
              <h2>售卖设置</h2>
            </div>
          </div>
          <p v-if="!id" class="empty-inline">保存商品档案后可设置售价库存。</p>
          <el-alert
            v-else-if="saleUnavailable"
            title="售卖信息暂不可用。请重新读取最新数据后再保存。"
            type="warning"
            :closable="false"
          /><el-form
            v-else
            :disabled="!!busy"
            label-position="top"
            @submit.prevent="saveSale"
            ><el-form-item label="演示售价（元）" required
              ><el-input
                v-model="sale.price"
                inputmode="decimal"
                placeholder="0.00"
                aria-label="演示售价"
                ><template #prefix>¥</template></el-input
              ></el-form-item
            ><el-form-item label="演示库存（件）" required
              ><el-input-number
                v-model="sale.stock"
                :min="0"
                :max="1000000"
                :precision="0"
                controls-position="right"
                aria-label="演示库存"
            /></el-form-item>
            <div class="sale-switch">
              <div>
                <strong>开启售卖</strong
                ><small>{{
                  product?.status === "PUBLISHED"
                    ? "开售后，有库存的商品显示可购买"
                    : "商品发布后才能开启售卖"
                }}</small>
              </div>
              <el-switch
                v-model="sale.onSale"
                :disabled="product?.status !== 'PUBLISHED' && !sale.onSale"
                aria-label="开启售卖"
              />
            </div>
            <el-button
              type="primary"
              native-type="submit"
              class="full-width"
              :loading="busy === 'sale'"
              :disabled="!!busy && busy !== 'sale'"
              >保存售卖设置</el-button
            >
            <p class="fine-print muted">
              隐藏前请先关闭开售并保存。售价和库存仅用于演示。
            </p></el-form
          >
        </section>
        <div class="editor-tip">
          <Icon name="shield" :size="23" />
          <div>
            <strong>保持信息一致</strong>
            <p>
              规格、资料与售卖设置分别保存。遇到冲突时先核对最新数据，避免覆盖其他修改。
            </p>
          </div>
        </div>
      </aside>
    </div>
    <div v-else class="empty-state">
      <h2>无法加载商品</h2>
      <el-button @click="load">重试</el-button>
    </div>
  </div>
</template>
