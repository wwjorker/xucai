<script setup>
import { ref, reactive, watch } from "vue";
import { api } from "../api";
const props = defineProps({
  order: Object,
  merchant: Boolean,
  loading: Boolean,
});
const emit = defineEmits(["updated", "busy"]);
const scope = props.merchant ? "merchant" : "buyer";
const base = props.merchant
  ? "/trade/admin/purchase-orders/"
  : "/trade/purchase-orders/";
const key = "xucai.fulfillment." + scope + "." + props.order.id;
const pending = ref(null),
  busy = ref(false),
  error = ref("");
const shipment = reactive({ carrier: "", trackingNo: "", note: "" });
const reason = ref("");
const resolutions = reactive({});
function draftFor(id) {
  return (resolutions[id] ||= { status: "RESOLVED", resolution: "" });
}
try {
  const draft = JSON.parse(sessionStorage.getItem(key + ".draft") || "null");
  if (draft) {
    Object.assign(shipment, draft.shipment);
    reason.value = draft.reason || "";
    Object.assign(resolutions, draft.resolutions || {});
  }
} catch {
  /* 存储不可用时保留本页输入 */
}
watch(
  [shipment, reason, resolutions],
  () => {
    try {
      sessionStorage.setItem(
        key + ".draft",
        JSON.stringify({ shipment, reason: reason.value, resolutions }),
      );
    } catch {
      /* 本页仍保留输入 */
    }
  },
  { deep: true, flush: "sync" },
);
try {
  const saved = JSON.parse(sessionStorage.getItem(key) || "null");
  if (
    saved &&
    ["ship", "complete", "after-sales", "resolve"].includes(saved.kind)
  )
    pending.value = saved;
} catch {
  /* 当前页面仍可继续 */
}
function keep(value) {
  pending.value = value;
  try {
    value
      ? sessionStorage.setItem(key, JSON.stringify(value))
      : sessionStorage.removeItem(key);
  } catch {
    /* 页面内保留冻结请求 */
  }
}
async function submit(kind, body, caseId) {
  if (busy.value || props.loading) return;
  if (!pending.value) keep({ kind, body, caseId });
  const request = pending.value;
  busy.value = true;
  emit("busy", true);
  error.value = "";
  try {
    const suffix =
      request.kind === "resolve"
        ? "after-sales/" + request.caseId + "/resolve"
        : request.kind;
    const updated = await api(base + props.order.id + "/" + suffix, {
      scope,
      method: "POST",
      body: request.body,
    });
    emit("updated", updated);
    keep(null);
    if (request.kind === "after-sales") reason.value = "";
    if (request.kind === "resolve") delete resolutions[request.caseId];
    if (request.kind === "ship")
      Object.assign(shipment, { carrier: "", trackingNo: "", note: "" });
  } catch (e) {
    error.value = e.message;
    if ([400, 403, 404, 409].includes(e.code)) {
      keep(null);
      try {
        emit("updated", await api(base + props.order.id, { scope }));
      } catch {
        /* 可刷新重查 */
      }
    }
  } finally {
    busy.value = false;
    emit("busy", false);
  }
}
function apply() {
  submit("after-sales", {
    requestId: crypto.randomUUID(),
    reason: reason.value.trim(),
  });
}
const label = (kind) =>
  ({
    ship: "登记发货",
    complete: "确认收货",
    "after-sales": "申请售后",
    resolve: "保存处理结论",
  })[kind];
const openCase = () =>
  props.order.afterSales?.some((item) => item.status === "OPEN");
</script>
<template>
  <section class="fulfillment-card">
    <h2>物流与收货</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="pending" class="pending" role="status">
      <p>
        正在核对“{{
          label(pending.kind)
        }}”结果。请求内容已固定，请重试原操作；离开后再进入也可继续。
      </p>
      <p v-if="pending.body">
        {{ pending.body.carrier }} {{ pending.body.trackingNo }}
        {{ pending.body.note }} {{ pending.body.reason }}
        {{ pending.body.resolution }}
      </p>
      <el-button :loading="busy" :disabled="loading" @click="submit()"
        >重试{{ label(pending.kind) }}</el-button
      >
    </div>
    <template v-if="order.shipment">
      <p>承运方：{{ order.shipment.carrier }}</p>
      <p>物流单号：{{ order.shipment.trackingNo }}</p>
      <p v-if="order.shipment.note">发货说明：{{ order.shipment.note }}</p>
      <p>登记时间：{{ order.shippedAt }}</p>
      <p class="muted">以上为商家登记信息，未接入物流公司实时轨迹。</p>
    </template>
    <p v-else>尚未登记发货信息。</p>
    <p v-if="order.completedAt">客户已确认收货：{{ order.completedAt }}</p>
    <p v-if="openCase()" class="pending">
      此订单有待处理售后，处理完成后再继续发货或确认收货。
    </p>
    <form
      v-if="merchant && order.status === 'PAID'"
      @submit.prevent="submit('ship', { ...shipment })"
    >
      <fieldset :disabled="busy || loading || !!pending || openCase()">
        <legend>登记发货</legend>
        <label
          >承运方<input
            v-model="shipment.carrier"
            required
            maxlength="80"
            placeholder="填写承运公司或配送方"
        /></label>
        <label
          >物流单号<input
            v-model="shipment.trackingNo"
            required
            maxlength="80"
            placeholder="填写本次配送单号"
        /></label>
        <label
          >发货说明<textarea
            v-model="shipment.note"
            maxlength="500"
            rows="3"
            placeholder="可填写件数及交付注意事项"
          />
        </label>
        <p class="muted">请核对收货约定与设备清单。提交后保留原物流信息。</p>
        <button type="submit" class="primary">保存发货信息</button>
      </fieldset>
    </form>
    <template v-if="!merchant && order.status === 'SHIPPED'"
      ><p>确认已收到本订单设备后，再点击确认收货。</p>
      <el-button
        type="primary"
        :disabled="busy || loading || !!pending || openCase()"
        @click="submit('complete')"
        >确认已收到设备</el-button
      >
    </template>
  </section>
  <section class="fulfillment-card">
    <h2>售后申请与处理</h2>
    <p class="muted">
      记录设备问题和人工处理结果。此处不会自动退款、补发或变更库存；处理说明应如实填写已完成事项。
    </p>
    <p v-if="!order.afterSales?.length">暂无售后记录。</p>
    <article v-for="item in order.afterSales" :key="item.id" class="case">
      <span class="status-pill">{{
        { OPEN: "待处理", RESOLVED: "已处理", REJECTED: "不予处理" }[
          item.status
        ]
      }}</span>
      <p>申请时间：{{ item.createdAt }}</p>
      <p class="prose">{{ item.reason }}</p>
      <template v-if="item.status !== 'OPEN'"
        ><p class="prose">处理说明：{{ item.resolution }}</p>
        <p>处理时间：{{ item.reviewedAt }}</p></template
      >
      <form
        v-if="merchant && item.status === 'OPEN'"
        @submit.prevent="submit('resolve', { ...draftFor(item.id) }, item.id)"
      >
        <fieldset :disabled="busy || loading || !!pending">
          <legend>填写人工处理结果</legend>
          <label
            >处理结论<select v-model="draftFor(item.id).status">
              <option value="RESOLVED">已处理</option>
              <option value="REJECTED">不予处理</option>
            </select></label
          >
          <label
            >处理说明<textarea
              v-model="draftFor(item.id).resolution"
              required
              maxlength="2000"
              rows="4"
              placeholder="填写核实结果、实际处理事项及说明"
            />
          </label>
          <button type="submit" class="primary">保存处理结论</button>
        </fieldset>
      </form>
    </article>
    <form
      v-if="
        !merchant &&
        ['PAID', 'SHIPPED', 'COMPLETED'].includes(order.status) &&
        !openCase()
      "
      @submit.prevent="apply"
    >
      <fieldset :disabled="busy || loading || !!pending">
        <legend>提交售后问题</legend>
        <label
          >问题描述<textarea
            v-model="reason"
            required
            maxlength="1000"
            rows="4"
            placeholder="说明涉及的设备、问题表现和希望如何处理"
          />
        </label>
        <button type="submit" class="primary">提交售后申请</button>
      </fieldset>
    </form>
  </section>
</template>
<style scoped>
.fulfillment-card {
  padding: 28px;
  margin: 22px 0;
  border: 1px solid #e4e9e2;
  border-radius: 12px;
  background: white;
  overflow-wrap: anywhere;
}
p {
  margin: 12px 0;
  line-height: 1.7;
}
fieldset {
  border: 0;
  padding: 0;
  margin-top: 20px;
  min-width: 0;
}
legend {
  font-weight: 600;
  margin-bottom: 16px;
}
label {
  display: block;
  margin: 14px 0;
  line-height: 1.7;
}
input,
textarea,
select {
  display: block;
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #cfd9d1;
  border-radius: 6px;
  padding: 10px 12px;
  font: inherit;
  background: white;
  color: inherit;
}
input:focus,
textarea:focus,
select:focus {
  outline: 2px solid #557b66;
  outline-offset: 2px;
}
.primary {
  background: #244e40;
  color: white;
  border: 0;
  border-radius: 6px;
  padding: 11px 18px;
  font: inherit;
  cursor: pointer;
}
fieldset:disabled .primary {
  opacity: 0.5;
  cursor: default;
}
.case {
  border-top: 1px solid #e4e9e2;
  padding: 20px 0;
}
.pending {
  background: #f8f2e4;
  padding: 14px;
  border-radius: 6px;
}
.prose {
  white-space: pre-wrap;
}
@media (max-width: 600px) {
  .fulfillment-card {
    padding: 18px;
  }
}
</style>
