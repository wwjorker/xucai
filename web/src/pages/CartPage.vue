<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { api, sessions } from "../api";
import { money } from "../domain";
import ProductVisual from "../components/ProductVisual.vue";
let alive = true;
const owner = () => String(sessions.buyer.user?.id || "");
const valid = (account) => alive && account === owner();
onBeforeUnmount(() => {
  alive = false;
});

const router = useRouter(),
  cart = ref(null),
  products = ref({}),
  busy = ref(false),
  error = ref("");
const address = reactive({ recipient: "", phone: "", detail: "" });
const ready = ref(false);
const total = computed(() =>
  (cart.value?.items || []).reduce(
    (sum, line) =>
      sum +
      Number(products.value[line.productId]?.sale?.price || 0) *
        Number(line.quantity),
    0,
  ),
);
const available = computed(
  () =>
    cart.value?.items.length &&
    cart.value.items.every(
      (line) =>
        products.value[line.productId]?.purchasable &&
        Number(line.quantity) <=
          Number(products.value[line.productId]?.sale?.stock),
    ),
);
async function readCart(account = owner()) {
  if (!valid(account)) return;
  ready.value = false;
  const value = await api("/trade/cart", { scope: "buyer" });
  if (!valid(account)) return;
  cart.value = value;
  const details = await Promise.all(
    value.items.map(async (line) => {
      try {
        return [
          line.productId,
          await api("/catalog/products/" + encodeURIComponent(line.productId)),
        ];
      } catch {
        return [line.productId, null];
      }
    }),
  );
  if (!valid(account)) return;
  products.value = Object.fromEntries(details);
  ready.value = true;
}
async function load() {
  if (busy.value || !alive) return;
  const account = owner();
  busy.value = true;
  error.value = "";
  try {
    await readCart(account);
  } catch (e) {
    if (!valid(account)) return;
    error.value = e.message;
  } finally {
    if (valid(account)) busy.value = false;
  }
}
async function change(productId, quantity) {
  if (!alive || busy.value || !ready.value) return;
  if (!Number.isInteger(quantity) || quantity < 0 || quantity > 999) return;
  const account = owner();
  busy.value = true;
  error.value = "";
  ready.value = false;
  try {
    const items = cart.value.items
      .map((line) =>
        String(line.productId) === String(productId)
          ? { ...line, quantity }
          : line,
      )
      .filter((line) => line.quantity > 0);
    await api("/trade/cart", {
      scope: "buyer",
      method: "PUT",
      retry: false,
      body: { expectedVersion: cart.value.version, items },
    });
    await readCart(account);
  } catch (e) {
    if (!valid(account)) return;
    error.value = e.message + "；请刷新清单核对，未自动重复提交。";
  } finally {
    if (valid(account)) busy.value = false;
  }
}
async function prepare() {
  if (!alive || busy.value || !ready.value || !available.value) return;
  const value = Object.fromEntries(
    Object.entries(address).map(([k, v]) => [k, v.trim()]),
  );
  if (
    !value.recipient ||
    !value.detail ||
    !/^(?=(?:\D*\d){5})[0-9+() -]{5,30}$/.test(value.phone)
  ) {
    error.value = "请填写收件人、有效联系电话和完整收货地址。";
    return;
  }
  const account = owner();
  busy.value = true;
  error.value = "";
  try {
    const intent = await api("/trade/checkout-intents", {
      scope: "buyer",
      method: "POST",
      retry: false,
      body: { expectedVersion: cart.value.version, address: value },
    });
    if (!valid(account)) return;
    await router.push("/checkout/" + encodeURIComponent(intent.id));
  } catch (e) {
    if (!valid(account)) return;
    error.value = e.message + "；此次未确认下单，请核对清单后再操作。";
    ready.value = false;
  } finally {
    if (valid(account)) busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-container cart-page">
    <RouterLink to="/" class="back-link">继续选购</RouterLink>
    <header>
      <h1>采购清单</h1>
      <el-button :disabled="busy" @click="load">刷新清单</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!cart && busy">正在读取清单…</p>
    <p v-else-if="cart && !cart.items.length">
      清单还是空的，先去选几件设备吧。
    </p>
    <template v-if="cart?.items.length">
      <article
        v-for="line in cart.items"
        :key="line.productId"
        class="cart-line"
      >
        <div class="visual">
          <ProductVisual
            v-if="products[line.productId]"
            :product="products[line.productId]"
          />
        </div>
        <div class="line-info">
          <RouterLink :to="'/products/' + line.productId">{{
            products[line.productId]?.name || "商品暂不可用"
          }}</RouterLink>
          <p>
            {{
              products[line.productId]?.purchaseMessage ||
              "暂时无法核对商品，请刷新或移除"
            }}
          </p>
          <strong v-if="products[line.productId]?.priceAvailable"
            >¥ {{ money(products[line.productId].sale.price) }} / 件</strong
          >
        </div>
        <el-input-number
          :model-value="Number(line.quantity)"
          :min="1"
          :max="999"
          :disabled="busy || !ready"
          aria-label="购买数量"
          @change="(value) => change(line.productId, value)"
        />
        <el-button :disabled="busy || !ready" @click="change(line.productId, 0)"
          >移除</el-button
        >
      </article>
      <section class="address-card">
        <h2>收货信息</h2>
        <el-form label-position="top"
          ><el-form-item label="收件人"
            ><el-input
              v-model="address.recipient"
              maxlength="40"
              :disabled="busy"
              autocomplete="name" /></el-form-item
          ><el-form-item label="联系电话"
            ><el-input
              v-model="address.phone"
              maxlength="30"
              :disabled="busy"
              autocomplete="tel" /></el-form-item
          ><el-form-item label="收货地址"
            ><el-input
              v-model="address.detail"
              type="textarea"
              maxlength="300"
              :disabled="busy"
              autocomplete="street-address" /></el-form-item
        ></el-form>
        <p v-if="available">
          当前合计 ¥ {{ money(total) }}，请以下一步确认页显示的金额为准。
        </p>
        <p v-if="!available">部分商品已不可购买或库存不足，请调整清单。</p>
        <el-button
          type="primary"
          :disabled="busy || !ready || !available"
          @click="prepare"
          >核对订单</el-button
        >
        <p class="muted">下一步核对后才会创建订单。</p>
      </section>
    </template>
  </div>
</template>
<style scoped>
.cart-page {
  padding-bottom: 64px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 24px 0;
}
.cart-line,
.address-card {
  background: #fff;
  border: 1px solid #e3e8e3;
  border-radius: 16px;
  padding: 24px;
  margin: 18px 0;
}
.cart-line {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.visual {
  width: 90px;
  height: 90px;
  overflow: hidden;
}
.line-info {
  flex: 1;
  min-width: 180px;
}
.line-info p,
.address-card p {
  margin: 14px 0;
}
.address-card {
  max-width: 680px;
}
@media (max-width: 600px) {
  .cart-line,
  .address-card {
    padding: 18px;
  }
  .visual {
    width: 64px;
    height: 64px;
  }
}
</style>
