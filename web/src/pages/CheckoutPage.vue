<script setup>
import { ref, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, sessions } from "../api";
import { money } from "../domain";
let alive = true;
const owner = () => String(sessions.buyer.user?.id || "");
const valid = (account) => alive && account === owner();
onBeforeUnmount(() => {
  alive = false;
});

const checked = ref(false);
const id = useRoute().params.id,
  router = useRouter(),
  intent = ref(null),
  busy = ref(false),
  error = ref(""),
  uncertain = ref(false);
async function read(account = owner()) {
  const value = await api("/trade/checkout-intents/" + encodeURIComponent(id), {
    scope: "buyer",
  });
  if (!valid(account)) return;
  intent.value = value;
  checked.value = true;
  if (intent.value.orderId)
    await router.replace("/purchase-orders/" + intent.value.orderId);
}
async function load() {
  if (busy.value || !alive) return;
  const account = owner();
  busy.value = true;
  error.value = "";
  try {
    await read(account);
  } catch (e) {
    if (!valid(account)) return;
    error.value = e.message;
  } finally {
    if (valid(account)) busy.value = false;
  }
}
async function confirm() {
  if (
    !alive ||
    busy.value ||
    !intent.value ||
    intent.value.orderId ||
    (uncertain.value && !checked.value)
  )
    return;
  const account = owner();
  busy.value = true;
  error.value = "";
  try {
    const order = await api(
      "/trade/checkout-intents/" + encodeURIComponent(id) + "/confirm",
      { scope: "buyer", method: "POST", retry: false },
    );
    if (!valid(account)) return;
    uncertain.value = false;
    await router.replace("/purchase-orders/" + order.id);
  } catch (e) {
    if (!valid(account)) return;
    error.value = e.message;
    uncertain.value = true;
    checked.value = false;
  } finally {
    if (valid(account)) busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-container checkout-page">
    <RouterLink to="/cart" class="back-link">返回采购清单</RouterLink>
    <h1>确认采购订单</h1>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="uncertain">
      请先刷新核对结果。如仍需重试，将继续使用本次确认编号，不会另建一份订单。
    </p>
    <el-button :disabled="busy" @click="load">刷新核对</el-button>
    <template v-if="intent"
      ><section>
        <h2>{{ intent.snapshot.summary }}</h2>
        <article v-for="line in intent.snapshot.items" :key="line.productId">
          <strong>{{ line.name }}</strong>
          <p>{{ line.quantity }} 件 × ¥ {{ money(line.unitPrice) }}</p>
        </article>
        <h2>
          合计 ¥
          {{
            money(
              intent.snapshot.items.reduce(
                (sum, line) =>
                  sum + Number(line.unitPrice) * Number(line.quantity),
                0,
              ),
            )
          }}
        </h2>
      </section>
      <section>
        <h2>收货信息</h2>
        <p>{{ intent.address.recipient }} · {{ intent.address.phone }}</p>
        <p>{{ intent.address.detail }}</p>
        <p>
          确认信息有效至
          {{ intent.expiresAt }}。修改商品或收货信息，请返回清单重新核对。
        </p>
      </section>
      <p>当前为演示购买，不收取真实款项。确认后创建待支付订单并预留库存。</p>
      <el-button
        type="primary"
        :disabled="busy || !!intent.orderId || (uncertain && !checked)"
        @click="confirm"
        >{{ uncertain ? "重试本次确认" : "确认下单" }}</el-button
      ></template
    >
  </div>
</template>
<style scoped>
.checkout-page {
  padding-bottom: 64px;
}
.checkout-page h1 {
  margin: 24px 0;
}
.checkout-page section {
  margin: 24px 0;
  padding: 24px;
  border: 1px solid #e3e8e3;
  background: #fff;
  border-radius: 16px;
}
.checkout-page p {
  margin: 16px 0;
  overflow-wrap: anywhere;
}
.checkout-page article {
  padding: 18px 0;
  border-bottom: 1px solid #eef0ec;
}
</style>
