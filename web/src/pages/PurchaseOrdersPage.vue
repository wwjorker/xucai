<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from "vue";
import { useRoute } from "vue-router";
import { api } from "../api";
import { money } from "../domain";
import OrderFulfillmentPanel from "../components/OrderFulfillmentPanel.vue";
import AiPlanOrigin from "../components/AiPlanOrigin.vue";
const route = useRoute(),
  merchant = !!route.meta.merchant,
  scope = merchant ? "merchant" : "buyer",
  id = route.params.id;
const base = merchant ? "/trade/admin/purchase-orders" : "/trade/purchase-orders",
  uiBase = merchant ? "/merchant/purchase-orders" : "/purchase-orders";
const order = ref(null),
  items = ref([]),
  total = ref(0),
  page = ref(1),
  busy = ref(false),
  error = ref("");
const pendingAction = ref("");
const fulfillmentBusy = ref(false);
const pendingKey = "xucai.order-action." + id;
try {
  const saved = sessionStorage.getItem(pendingKey);
  if (["demo-pay", "cancel"].includes(saved)) pendingAction.value = saved;
} catch {
  /* 禁用存储时本页继续保护 */
}
watch(
  pendingAction,
  (action) => {
    try {
      if (action) sessionStorage.setItem(pendingKey, action);
      else sessionStorage.removeItem(pendingKey);
    } catch {
      /* 当前页面仍保留待核对动作 */
    }
  },
  { flush: "sync" },
);
async function act(action) {
  if (busy.value || (pendingAction.value && pendingAction.value !== action))
    return;
  pendingAction.value = action;
  busy.value = true;
  error.value = "";
  try {
    order.value = await api(base + "/" + id + "/" + action, {
      scope,
      method: "POST",
    });
    pendingAction.value = "";
  } catch (e) {
    error.value = e.message;
    if ([400, 403, 404, 409].includes(e.code)) {
      try {
        order.value = await api(base + "/" + id, { scope });
        pendingAction.value = "";
      } catch {
        /* 保留原操作，等待重试或核对 */
      }
    }
  } finally {
    busy.value = false;
  }
}
let alive = true,
  queued = false;
const state = (s) =>
  ({
    NO_PAY: "待支付",
    PAID: "已支付",
    CLOSED: "已关闭",
    SHIPPED: "已发货",
    COMPLETED: "已完成",
  })[s] || s;
async function load(next = page.value) {
  if (busy.value || fulfillmentBusy.value) return;
  busy.value = true;
  error.value = "";
  const filter = String(route.query.requirementId || "");
  try {
    const q = new URLSearchParams({ page: String(next), size: "20" });
    if (route.query.requirementId)
      q.set("requirementId", String(route.query.requirementId));
    const d = await api(id ? base + "/" + id : base + "?" + q, { scope });
    if (alive && filter === String(route.query.requirementId || "")) {
      if (id) {
        order.value = d;
        // NO_PAY可能是未完成请求之前的读；只有终态能够解除不确定操作。
        if (d.status !== "NO_PAY") pendingAction.value = "";
      } else {
        items.value = d.items;
        total.value = Number(d.total);
        page.value = next;
      }
    }
  } catch (e) {
    if (alive && filter === String(route.query.requirementId || ""))
      error.value = e.message;
  } finally {
    busy.value = false;
    if (alive && queued) {
      queued = false;
      load(1);
    }
  }
}
watch(
  () => route.query.requirementId,
  () => {
    if (id) return;
    items.value = [];
    total.value = 0;
    if (busy.value) queued = true;
    else load(1);
  },
);
onMounted(() => load());
onBeforeUnmount(() => (alive = false));
</script>
<template>
  <div class="page-container orders-page">
    <RouterLink v-if="id" :to="uiBase" class="back-link"
      >返回采购订单</RouterLink
    >
    <section class="page-heading">
      <div>
        <p class="eyebrow">PROCUREMENT ORDERS</p>
        <h1>
          {{ id ? "采购订单详情" : merchant ? "客户采购订单" : "我的采购订单" }}
        </h1>
        <p>查看已保存的设备清单、金额与收货约定。</p>
      </div>
      <el-button :disabled="busy || fulfillmentBusy" @click="load()"
        >刷新订单</el-button
      >
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="id && order">
      <section class="order-card">
        <span class="status-pill">{{ state(order.status) }}</span>
        <h2>{{ order.snapshot.summary }}</h2>
        <p>订单号：{{ order.id }}</p>
        <p>创建时间：{{ order.createdAt }}</p>
        <h3>合计 ¥ {{ money(order.totalAmount) }}</h3>
        <template v-if="order.status === 'NO_PAY'">
          <p>支付截止：{{ order.expiresAt }}（服务器时间）</p>
          <p>设备数量已预留。到期自动关闭并归还库存；扫描可能稍有延迟。</p>
          <p v-if="!merchant">
            演示支付仅记录演示状态，不收取真实款项。取消后如需重新采购，请重新选购。
          </p>
          <p v-if="pendingAction" role="status">
            正在核对{{
              pendingAction === "demo-pay" ? "演示支付" : "取消订单"
            }}结果。请重试原操作或刷新订单。
          </p>
          <div v-if="!merchant" class="pagination">
            <el-button
              type="primary"
              :disabled="
                busy || (!!pendingAction && pendingAction !== 'demo-pay')
              "
              @click="act('demo-pay')"
              >{{
                pendingAction === "demo-pay" ? "重试演示支付" : "演示支付"
              }}</el-button
            >
            <el-button
              :disabled="
                busy || (!!pendingAction && pendingAction !== 'cancel')
              "
              @click="act('cancel')"
              >{{
                pendingAction === "cancel"
                  ? "重试取消订单"
                  : "取消订单并归还库存"
              }}</el-button
            >
          </div>
        </template>
        <p v-else-if="['PAID', 'SHIPPED', 'COMPLETED'].includes(order.status)">
          演示支付已记录（{{ order.payChannel }}） · {{ order.paidAt }}。
        </p>
        <template v-else-if="order.status === 'CLOSED'">
          <p>
            {{
              order.closeReason === "TIMEOUT"
                ? "超过支付期限自动关闭"
                : "客户取消"
            }}
            · {{ order.closedAt }}。订单库存已归还。
          </p>
          <p v-if="order.orderSource !== 'DIRECT'">
            原需求与方案保留成单历史；再次采购可重新选购。
          </p>
          <RouterLink v-else to="/">重新选购</RouterLink>
        </template>
        <RouterLink
          v-if="order.planId"
          :to="
            merchant
              ? '/merchant/plans/' + order.planId + '/released'
              : '/plans/' + order.planId
          "
          >查看原方案与核实依据 →</RouterLink
        >
      </section>
      <section class="order-card">
        <h2>收货约定</h2>
        <p>{{ order.address.recipient }} · {{ order.address.phone }}</p>
        <p>{{ order.address.detail }}</p>
        <p class="muted">此处显示成单时保存的信息。</p>
      </section>
      <section class="order-card">
        <h2>设备清单</h2>
        <article v-for="i in order.snapshot.items" :key="i.productId">
          <h3>{{ i.name }} × {{ i.quantity }}</h3>
          <p>¥ {{ money(i.unitPrice) }} / 件 · 规格第 {{ i.specVersion }} 版</p>
          <details>
            <summary>查看成单时规格</summary>
            <pre>{{ JSON.stringify(i.specJson, null, 2) }}</pre>
          </details>
        </article>
      </section>
      <section v-if="order.snapshot.review" class="order-card">
        <h2>商家核实说明</h2>
        <p>{{ order.snapshot.review.note }}</p>
        <p>
          规格快照保留当时约定；若发现实际设备与约定不符，需要商家核查处理。
        </p>
      </section>
      <AiPlanOrigin :origin="order.snapshot.aiOrigin" published />
      <OrderFulfillmentPanel
        :order="order"
        :merchant="merchant"
        :loading="busy"
        @busy="fulfillmentBusy = $event"
        @updated="order = $event"
      />
    </template>
    <template v-else-if="!id"
      ><p v-if="!items.length && !busy">暂无采购订单。</p>
      <article v-for="item in items" :key="item.id" class="order-card">
        <span class="status-pill">{{ state(item.status) }}</span>
        <h2>采购订单 · ¥ {{ money(item.totalAmount) }}</h2>
        <p>{{ item.id }}</p>
        <p>
          {{
            item.orderSource === "DIRECT"
              ? "直接选购"
              : "需求 " + item.requirementId
          }}
          · {{ item.createdAt }}
        </p>
        <p v-if="Number(item.openAfterSaleCount) > 0">有待处理售后</p>
        <RouterLink :to="uiBase + '/' + item.id">查看订单 →</RouterLink>
      </article>
      <div class="pagination">
        <el-button :disabled="busy || page === 1" @click="load(page - 1)"
          >上一页</el-button
        ><span>第 {{ page }} 页 · 共 {{ total }} 单</span
        ><el-button
          :disabled="busy || page * 20 >= total"
          @click="load(page + 1)"
          >下一页</el-button
        >
      </div></template
    >
  </div>
</template>
<style scoped>
.orders-page {
  padding-bottom: 64px;
}
.order-card {
  padding: 28px;
  background: white;
  border: 1px solid #e4e9e2;
  border-radius: 12px;
  margin: 22px 0;
  overflow-wrap: anywhere;
}
.order-card p {
  margin: 12px 0;
  line-height: 1.7;
}
article article {
  border-top: 1px solid #eee;
  padding: 20px 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.pagination {
  display: flex;
  gap: 15px;
  align-items: center;
  flex-wrap: wrap;
}
@media (max-width: 600px) {
  .order-card {
    padding: 18px;
  }
}
</style>
