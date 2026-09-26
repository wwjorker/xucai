<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, sessions } from "../api";
import { categoryOf, money, safeUrl, specLabels, specValue } from "../domain";
import ProductVisual from "../components/ProductVisual.vue";
import ProductDocument from "../components/ProductDocument.vue";
import Icon from "../components/Icon.vue";
import { requestProductAssistant } from "../productAssistant";
const router = useRouter();
function askAssistant() {
  if (!product.value) return;
  if (!sessions.buyer.user) {
    router.push({ name: "buyer-login", query: { next: `/products/${product.value.id}?askAi=1` } });
    return;
  }
  requestProductAssistant(product.value, sessions.buyer.user.id);
  if (route.query.askAi) {
    const query = { ...route.query }; delete query.askAi;
    router.replace({ path: route.path, query });
  }
}
let alive = true;
const owner = () => String(sessions.buyer.user?.id || "");
const valid = (account) => alive && account === owner();
onBeforeUnmount(() => {
  alive = false;
});

const quantity = ref(1),
  adding = ref(false),
  cartNotice = ref(""),
  cartUncertain = ref(false);
async function checkCart() {
  if (adding.value || !alive) return;
  const account = owner();
  adding.value = true;
  try {
    const cart = await api("/trade/cart", { scope: "buyer" });
    if (!valid(account)) return;
    const item = cart.items.find(
      (line) => String(line.productId) === String(product.value.id),
    );
    cartNotice.value =
      "已核对：清单中现有此商品 " + Number(item?.quantity || 0) + " 件。";
    cartUncertain.value = false;
  } catch (e) {
    if (!valid(account)) return;
    cartNotice.value = e.message;
  } finally {
    if (valid(account)) adding.value = false;
  }
}
async function addToCart() {
  if (
    !alive ||
    adding.value ||
    cartUncertain.value ||
    !product.value?.purchasable ||
    !Number.isInteger(quantity.value)
  )
    return;
  const account = owner();
  adding.value = true;
  cartNotice.value = "";
  try {
    const cart = await api("/trade/cart", { scope: "buyer" });
    if (!valid(account)) return;
    const items = cart.items.map((line) => ({ ...line }));
    const line = items.find(
      (item) => String(item.productId) === String(product.value.id),
    );
    if (line) line.quantity = Number(line.quantity) + quantity.value;
    else items.push({ productId: product.value.id, quantity: quantity.value });
    if (items.some((item) => Number(item.quantity) > 999))
      throw new Error("每款商品最多999件，请到清单调整");
    await api("/trade/cart", {
      scope: "buyer",
      method: "PUT",
      retry: false,
      body: { expectedVersion: cart.version, items },
    });
    if (!valid(account)) return;
    cartNotice.value = "已加入采购清单";
  } catch (e) {
    if (!valid(account)) return;
    cartUncertain.value = true;
    cartNotice.value = e.message + "；请先核对清单，未自动重复提交。";
  } finally {
    if (valid(account)) adding.value = false;
  }
}
const route = useRoute(),
  product = ref(null),
  docs = ref([]),
  error = ref(""),
  docsError = ref(""),
  busy = ref(true);
const highlights = computed(() => Object.entries(product.value?.specJson || {})
  .filter(([key]) => specLabels[key] && !["source_region", "hardware_model"].includes(key))
  .slice(0, 3).map(([key, value]) => `${specLabels[key]}：${specValue(value)}`).join("；"));
async function load() {
  busy.value = true;
  error.value = "";
  docsError.value = "";
  try {
    const value = await api(
      "/catalog/products/" + encodeURIComponent(route.params.id),
    );
    if (!alive) return;
    product.value = value;
    if (route.query.askAi === "1" && sessions.buyer.user) askAssistant();
  } catch (e) {
    if (!alive) return;
    error.value = e.message;
    product.value = null;
  }
  if (!alive) return;
  if (product.value) {
    try {
      const value = await api(
        "/catalog/products/" + encodeURIComponent(route.params.id) + "/docs",
      );
      if (!alive) return;
      docs.value = value;
    } catch (e) {
      if (!alive) return;
      docsError.value = e.message;
    }
  }
  if (alive) busy.value = false;
}
onMounted(load);
</script>
<template>
  <div class="page-container detail-page">
    <RouterLink to="/" class="back-link"
      ><Icon name="back" :size="17" /> 返回设备选购</RouterLink
    >
    <el-skeleton v-if="busy" animated :rows="9" />
    <div v-else-if="error" class="empty-state">
      <Icon name="box" :size="48" />
      <h1>暂时无法查看这件设备</h1>
      <p>{{ error }}</p>
      <el-button @click="load">重新加载</el-button>
    </div>
    <template v-else-if="product"
      ><section class="product-hero">
        <div class="detail-image">
          <span class="category-label">{{
            categoryOf(product.categoryId).name
          }}</span
          ><ProductVisual :product="product" large />
        </div>
        <div class="product-summary">
          <div class="eyebrow">{{ product.brand }} / {{ product.series }}</div>
          <h1>{{ product.name }}</h1>
          <p class="product-description">{{ highlights || categoryOf(product.categoryId).name }}</p>
          <div class="detail-price-box">
            <span class="section-overline">演示售价</span
            ><strong class="detail-price" v-if="product.priceAvailable"
              ><small>¥</small>{{ money(product.sale?.price) }}</strong
            ><strong v-else class="unavailable-title">{{
              product.purchaseMessage
            }}</strong
            ><span :class="['availability', { available: product.purchasable }]"
              ><i></i>{{ product.purchaseMessage
              }}<template v-if="product.purchasable">
                · 当前库存 {{ product.sale.stock }} 件</template
              ></span
            >
          </div>
          <div class="purchase-actions">
            <el-input-number
              v-model="quantity"
              :min="1"
              :max="
                Math.min(999, Math.max(1, Number(product.sale?.stock || 1)))
              "
              :disabled="adding || !product.purchasable"
              aria-label="购买数量"
            />
            <el-button
              type="primary"
              :disabled="adding || cartUncertain || !product.purchasable"
              @click="addToCart"
              >加入采购清单</el-button
            >
            <el-button
              v-if="cartUncertain"
              :disabled="adding"
              @click="checkCart"
              >核对清单</el-button
            >
            <RouterLink to="/cart" class="cart-page-link">查看采购清单 →</RouterLink>
            <el-button @click="askAssistant">问问 AI</el-button>
            <p v-if="cartNotice" role="status">{{ cartNotice }}</p>
          </div>
          <section v-if="product.description" class="product-purchase-note">
            <h2>商品说明与购买提示</h2>
            <p>{{ product.description }}</p>
          </section>
          <div class="detail-notes">
            <Icon name="document" :size="19" /><span
              >下方可查看商品规格与来源资料</span
            >
          </div>
          <p class="muted fine-print">
            普通商品可加入清单直接购买；复杂设备方案可提交采购需求，由商家核实。当前支付仅用于项目演示。
          </p>
        </div>
      </section>
      <section class="detail-section">
        <div class="section-title">
          <div>
            <h2>规格参数</h2>
          </div>
        </div>
        <dl class="spec-table">
          <div v-for="(value, key) in product.specJson" :key="key">
            <dt>{{ specLabels[key] || key }}</dt>
            <dd>{{ specValue(value) }}</dd>
          </div>
        </dl>
      </section>
      <section class="detail-section">
        <div class="section-title">
          <div>
            <h2>商品资料</h2>
            <p class="muted">查看资料说明，或前往品牌官网核对完整信息。</p>
          </div>
          <span class="muted">{{ docs.length }} 份当前资料</span>
        </div>
        <el-alert
          v-if="docsError"
          type="warning"
          :title="docsError"
          :closable="false"
        />
        <div v-else-if="!docs.length" class="empty-inline">
          暂无可用的商品资料。
        </div>
        <article class="source-card" v-for="doc in docs" :key="doc.id">
          <div class="source-heading">
            <Icon name="document" :size="24" />
            <div>
              <h3>{{ doc.title }}</h3>
              <span class="muted">来源资料 · 请留意适用地区与型号</span>
            </div>
            <a
              v-if="safeUrl(doc.sourceUrl)"
              :href="safeUrl(doc.sourceUrl)"
              target="_blank"
              rel="noopener noreferrer"
              class="source-link"
              >查看来源 <Icon name="link" :size="16"
            /></a>
          </div>
          <ProductDocument class="source-content" :content="doc.content" />
          <details class="source-original">
            <summary>查看原始摘记</summary>
            <pre>{{ doc.content }}</pre>
            <small class="muted">资料版本 {{ doc.version }}</small>
          </details>
        </article>
      </section>
    </template>
  </div>
</template>
