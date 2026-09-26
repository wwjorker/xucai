
<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { api, query } from "../api";
import { categories, categoryOf, statusName } from "../domain";
import { highlight, contextWindow, searchParams, indexLabel, dropReason } from "../chunks.js";
import Icon from "../components/Icon.vue";

const route = useRoute(),
  router = useRouter(),
  form = reactive({
    keyword: route.query.keyword || "",
    categoryId: route.query.categoryId ? Number(route.query.categoryId) : null,
    productId: route.query.productId ? Number(route.query.productId) : null,
    docKey: route.query.docKey || "",
    includeUnpublished: route.query.includeUnpublished === "true",
  }),
  page = ref(Number(route.query.page) || 1),
  size = 20,
  result = ref(null),
  busy = ref(false),
  error = ref(""),
  located = ref(null),
  locating = ref(false),
  locateError = ref("");
let generation = 0;

const params = computed(() => searchParams(form, page.value, size));
const pages = computed(() => Math.ceil(Number(result.value?.total || 0) / size));

async function load() {
  const g = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const data = await api("/catalog/admin/doc-chunks?" + query(params.value), { scope: "merchant" });
    if (g !== generation) return;
    result.value = data;
    // 地址栏保留筛选条件，刷新后回到同一批结果。
    router.replace({ query: { ...params.value } });
  } catch (e) {
    if (g === generation) {
      error.value = e.message;
      result.value = null;
    }
  } finally {
    if (g === generation) busy.value = false;
  }
}
function search() {
  page.value = 1;
  load();
}
function reset() {
  Object.assign(form, { keyword: "", categoryId: null, productId: null, docKey: "", includeUnpublished: false });
  search();
}
function turn(to) {
  page.value = to;
  load();
}

async function locate(chunkId) {
  located.value = null;
  locateError.value = "";
  locating.value = true;
  try {
    located.value = await api("/catalog/admin/doc-chunks/locate?" + query({ chunkId }), { scope: "merchant" });
  } catch (e) {
    locateError.value = e.message;
  } finally {
    locating.value = false;
  }
}
const window_ = computed(() =>
  located.value ? contextWindow(located.value.content, located.value.charStart, located.value.charEnd, 240) : null,
);
async function copyId(chunkId) {
  try {
    await navigator.clipboard.writeText(chunkId);
    ElMessage.success("片段编号已复制：" + chunkId);
  } catch {
    ElMessage.info("请手动复制片段编号：" + chunkId);
  }
}
onMounted(load);
</script>

<template>
  <div class="page-container merchant-page chunk-page">
    <div class="merchant-breadcrumb">商家工作台 <span>/</span> 资料片段</div>
    <section class="page-heading">
      <div>
        <div class="eyebrow">DOCUMENT CHUNKS</div>
        <h1>在资料原文里，找到那一句。</h1>
        <p class="muted">
          片段由当前有效资料正文切分而来，每条都能回到原文位置与官方来源。
        </p>
      </div>
    </section>

    <el-alert type="info" :closable="false" show-icon class="panel-alert">
      <template #title>这里是关键词与结构化筛选，不是语义检索</template>
      <div class="notice-body">
        多个关键词之间是“同时包含”，按商品与片段顺序返回，不做相关度排序。<strong
          >找到引用只说明这句话出自该资料，不代表这款商品满足采购要求</strong
        >；是否满足仍由商家逐项核实。
        <span v-if="result?.vectorIndex" class="vector-note">
          向量索引：{{ result.vectorIndex.message }}
        </span>
      </div>
    </el-alert>

    <section class="panel">
      <div class="panel-toolbar">
        <h2>
          资料片段
          <small v-if="result">{{ result.total }} 条候选</small>
        </h2>
      </div>
      <form class="chunk-filters" @submit.prevent="search">
        <el-input
          v-model="form.keyword"
          clearable
          placeholder="关键词，空格分隔，最多5个"
          aria-label="片段关键词"
          @clear="search"
        >
          <template #prefix><Icon name="search" :size="18" /></template>
        </el-input>
        <el-select v-model="form.categoryId" clearable placeholder="全部分类" aria-label="分类筛选">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-input
          v-model="form.productId"
          clearable
          placeholder="商品编号"
          aria-label="商品编号筛选"
          inputmode="numeric"
        />
        <el-input v-model="form.docKey" clearable placeholder="资料标识" aria-label="资料标识筛选" />
        <el-checkbox v-model="form.includeUnpublished">包含未发布或已隐藏的商品</el-checkbox>
        <div class="filter-actions">
          <el-button type="primary" native-type="submit" :loading="busy">查找</el-button>
          <el-button :disabled="busy" @click="reset">清空条件</el-button>
        </div>
      </form>

      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="panel-alert" />
      <el-alert
        v-else-if="result?.dropped?.length"
        type="warning"
        :closable="false"
        show-icon
        class="panel-alert"
        :title="`有 ${result.dropped.length} 条片段未通过原文复核，已从结果中去掉`"
      >
        <ul class="drop-list">
          <li v-for="d in result.dropped" :key="d.chunkId">
            {{ d.chunkId }} —— {{ dropReason(d.reason) }}，请在商品页重建该资料的片段。
          </li>
        </ul>
      </el-alert>

      <div v-if="busy && !result" class="empty-inline">正在查找片段…</div>
      <div v-else-if="result && !result.items.length" class="empty-state">
        <Icon name="document" :size="28" />
        <p>没有符合条件的当前资料片段。</p>
        <p class="muted">
          可能是关键词太细、资料尚未建立片段，或者相关商品未发布。可在商品编辑页查看片段状态并重建。
        </p>
      </div>

      <ol v-else-if="result" class="chunk-list">
        <li v-for="item in result.items" :key="item.chunkId" class="chunk-card">
          <div class="chunk-head">
            <div>
              <RouterLink class="text-link" :to="`/merchant/products/${item.productId}/edit`">
                {{ item.productName }}
              </RouterLink>
              <small class="muted">
                {{ categoryOf(item.categoryId).name }} · 商品{{ item.productId }} ·
                {{ statusName(item.productStatus) }}
              </small>
            </div>
            <span class="status-pill" :class="item.productStatus.toLowerCase()">
              {{ statusName(item.productStatus) }}
            </span>
          </div>
          <div class="chunk-source">
            <Icon name="document" :size="16" />
            <span>{{ item.docTitle }}</span>
            <small>{{ item.docKey }} · 第 {{ item.version }} 版 · 第 {{ item.chunkNo }} 段</small>
          </div>
          <div v-if="item.headingPath" class="chunk-heading">{{ item.headingPath }}</div>
          <p class="chunk-text">
            <span v-for="(part, i) in highlight(item.text, item.matches)" :key="i" :class="{ hit: part.hit }">{{
              part.text
            }}</span>
          </p>
          <div class="chunk-foot">
            <code>{{ item.chunkId }}</code>
            <small class="muted">原文第 {{ item.charStart }}–{{ item.charEnd }} 字</small>
            <small v-if="item.oversize" class="muted">整句超长，未在句中切开</small>
            <span class="chunk-actions">
              <el-button size="small" @click="locate(item.chunkId)">查看原文位置</el-button>
              <el-button size="small" text @click="copyId(item.chunkId)">复制编号</el-button>
              <a class="source-link" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">
                <Icon name="link" :size="15" /> 官方来源
              </a>
            </span>
          </div>
        </li>
      </ol>

      <div v-if="pages > 1" class="pagination">
        <el-button :disabled="page <= 1 || busy" @click="turn(page - 1)">上一页</el-button>
        <span>第 {{ page }} / {{ pages }} 页</span>
        <el-button :disabled="page >= pages || busy" @click="turn(page + 1)">下一页</el-button>
      </div>
    </section>

    <el-dialog
      :model-value="!!located || !!locateError || locating"
      title="片段在原文中的位置"
      width="min(760px, 92vw)"
      class="locate-dialog"
      @update:model-value="
        () => {
          located = null;
          locateError = '';
        }
      "
    >
      <div v-if="locating" class="empty-inline">正在读取原文…</div>
      <el-alert v-else-if="locateError" :title="locateError" type="error" :closable="false" show-icon />
      <div v-else-if="located">
        <div class="locate-head">
          <strong>{{ located.docTitle }}</strong>
          <small class="muted">
            {{ located.productName }} · 商品{{ located.productId }} · {{ located.docKey }} · 第
            {{ located.version }} 版 · {{ statusName(located.docStatus) }}
          </small>
        </div>
        <el-alert
          :type="located.located && located.current ? 'success' : 'warning'"
          :closable="false"
          show-icon
          class="panel-alert"
          :title="located.note"
        >
          <div>
            索引状态：{{ indexLabel(located.indexState).label }}。
            {{ located.current ? "这是当前有效资料。" : "不能作为当前资料引用。" }}
            <span v-if="located.integrity && located.integrity !== 'OK'">
              这一条片段本身没通过复核：{{ dropReason(located.integrity) }}。
            </span>
          </div>
        </el-alert>
        <p v-if="located.headingPath" class="chunk-heading">{{ located.headingPath }}</p>
        <pre v-if="window_?.ok" class="locate-text"><span v-if="window_.clippedStart" class="muted">…</span><span>{{
          window_.before
        }}</span><mark>{{ window_.target }}</mark><span>{{ window_.after }}</span><span
            v-if="window_.clippedEnd"
            class="muted"
            >…</span
          ></pre>
        <el-alert
          v-else
          type="error"
          :closable="false"
          show-icon
          title="片段偏移与当前正文对不上，页面不猜测近似位置"
        />
        <div class="locate-foot">
          <code>{{ located.chunkId }}</code>
          <small class="muted">正文共 {{ located.contentLength }} 字，本段第 {{ located.charStart }}–{{
            located.charEnd
          }} 字</small>
          <a class="source-link" :href="located.sourceUrl" target="_blank" rel="noopener noreferrer">
            <Icon name="link" :size="15" /> 打开官方来源
          </a>
        </div>
        <div v-if="located.neighbours?.length" class="locate-neighbours">
          相邻片段：
          <el-button
            v-for="n in located.neighbours"
            :key="n.chunk_id"
            size="small"
            text
            @click="locate(n.chunk_id)"
          >
            第 {{ n.chunk_no }} 段{{ n.heading_path ? "（" + n.heading_path + "）" : "" }}
          </el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.chunk-page {
  padding-bottom: 64px;
}
.notice-body {
  line-height: 1.7;
}
.vector-note {
  display: block;
  margin-top: 6px;
}
.chunk-filters {
  display: grid;
  grid-template-columns: minmax(200px, 2fr) 150px 140px 150px;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
}
.chunk-filters .el-checkbox,
.filter-actions {
  grid-column: 1 / -1;
}
.filter-actions {
  display: flex;
  gap: 10px;
}
.chunk-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 16px;
}
.chunk-card {
  border: 1px solid #e4e9e2;
  border-radius: 12px;
  padding: 18px 20px;
  background: #fff;
  overflow-wrap: anywhere;
}
.chunk-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.chunk-head small {
  display: block;
  margin-top: 2px;
}
.chunk-source {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: #4c665b;
}
.chunk-source small {
  color: #7d8f86;
}
.chunk-heading {
  margin: 10px 0 0;
  font-size: 13px;
  color: #4c665b;
  background: #edf3ee;
  border-radius: 6px;
  padding: 4px 10px;
  display: inline-block;
}
.chunk-text {
  margin: 10px 0 12px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.chunk-text .hit {
  background: #ffe9a8;
  border-radius: 3px;
}
.chunk-foot,
.locate-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  border-top: 1px dashed #e4e9e2;
  padding-top: 10px;
}
.chunk-foot code,
.locate-foot code {
  font-size: 12px;
  background: #f2f5f1;
  border-radius: 5px;
  padding: 2px 7px;
}
.chunk-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.drop-list {
  margin: 6px 0 0;
  padding-left: 18px;
}
.locate-head {
  margin-bottom: 10px;
}
.locate-head small {
  display: block;
}
.locate-text {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  line-height: 1.85;
  background: #f7f8f5;
  border: 1px solid #e4e9e2;
  border-radius: 10px;
  padding: 16px;
  max-height: 340px;
  overflow: auto;
  margin: 12px 0;
}
.locate-text mark {
  background: #ffe9a8;
  padding: 1px 0;
}
.locate-neighbours {
  margin-top: 12px;
  color: #4c665b;
}
@media (max-width: 760px) {
  .chunk-filters {
    grid-template-columns: 1fr;
  }
  .chunk-actions {
    margin-left: 0;
  }
}
</style>
