<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api";
import { money } from "../domain";
const id = useRoute().params.id,
  router = useRouter(),
  plan = ref(null),
  error = ref(""),
  busy = ref(false),
  pending = ref(null);
const address = reactive({ recipient: "", phone: "", detail: "" });
let alive = true,
  bypass = false;
async function load() {
  if (busy.value || pending.value) return;
  busy.value = true;
  error.value = "";
  if (plan.value)
    plan.value.currentCheck = {
      status: "UNAVAILABLE",
      reasons: ["正在重新检查"],
    };
  try {
    const value = await api("/trade/plans/" + id, { scope: "buyer" });
    if (alive) plan.value = value;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function confirm() {
  if (busy.value) return;
  error.value = "";
  try {
    if (!pending.value) {
      if (
        plan.value?.status !== "PENDING_CONFIRM" ||
        plan.value?.currentCheck?.status !== "READY"
      )
        throw new Error("当前方案无法确认，请重新检查或查看已有订单");
      const value = {
        recipient: address.recipient.trim(),
        phone: address.phone.trim(),
        detail: address.detail.trim(),
      };
      if (
        !value.recipient ||
        !value.detail ||
        !/^(?=(?:\D*\d){5})[0-9+() -]{5,30}$/.test(value.phone)
      )
        throw new Error("请填写收件人、有效联系电话和完整收货地址");
      pending.value = { address: value };
    }
    busy.value = true;
    const result = await api("/trade/plans/" + id + "/confirm", {
      scope: "buyer",
      method: "POST",
      body: pending.value,
    });
    pending.value = null;
    bypass = true;
    ElMessage.success("订单已建立");
    await router.replace("/purchase-orders/" + result.id);
  } catch (e) {
    error.value = e.message;
    if ([400, 403, 404, 409].includes(e.code)) pending.value = null;
    if (plan.value && [409, 503].includes(e.code))
      plan.value.currentCheck = {
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
    ElMessage.warning("请先核对本次下单结果");
    return false;
  }
  if (!Object.values(address).some(Boolean)) return true;
  try {
    await ElMessageBox.confirm(
      "收货信息尚未提交，离开会丢失输入。",
      "离开确认页？",
      { confirmButtonText: "离开", cancelButtonText: "继续填写" },
    );
    return true;
  } catch {
    return false;
  }
});
function unload(e) {
  if (pending.value || Object.values(address).some(Boolean)) {
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
  <div class="page-container checkout-page">
    <RouterLink :to="'/plans/' + id" class="back-link"
      >返回方案与核实依据</RouterLink
    >
    <section class="page-heading">
      <div>
        <p class="eyebrow">CONFIRM YOUR ORDER</p>
        <h1>确认采购订单</h1>
        <p>核对设备、金额和收货信息。确认后建立待支付订单并扣减可用库存。</p>
      </div>
      <el-button :disabled="busy || !!pending" @click="load"
        >重新检查</el-button
      >
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert
      v-if="pending"
      title="下单结果待核对，收货信息已锁定；重试本次确认不会重复扣库存。"
      type="warning"
      :closable="false"
    />
    <template v-if="plan">
      <section class="order-card">
        <h2>{{ plan.summary }}</h2>
        <p>方案 {{ plan.planNo }} · 需求第 {{ plan.requirementRevision }} 版</p>
        <p v-for="i in plan.snapshot.items" :key="i.productId">
          {{ i.name }} × {{ i.quantity }} · ¥ {{ money(i.unitPrice) }} / 件
        </p>
        <h3>订单总额 ¥ {{ money(plan.totalAmount) }}</h3>
        <p>价格为演示数据；当前没有真实收款。</p>
      </section>
      <section v-if="plan.orderId" class="order-card">
        <h2>此方案已有订单</h2>
        <p>重复确认不会更改原收货信息。</p>
        <RouterLink :to="'/purchase-orders/' + plan.orderId"
          >查看原订单 →</RouterLink
        >
      </section>
      <template v-else>
        <el-alert
          v-if="plan.currentCheck?.status !== 'READY'"
          title="当前检查未通过，暂不能建立新订单"
          type="warning"
          :closable="false"
        />
        <ul>
          <li v-for="reason in plan.currentCheck?.reasons" :key="reason">
            {{ reason }}
          </li>
        </ul>
        <section class="order-card">
          <h2>收货信息</h2>
          <el-form label-position="top" :disabled="busy || !!pending">
            <el-form-item label="收件人"
              ><el-input
                v-model="address.recipient"
                aria-label="收件人"
                maxlength="40"
                autocomplete="name"
            /></el-form-item>
            <el-form-item label="联系电话"
              ><el-input
                v-model="address.phone"
                aria-label="联系电话"
                maxlength="30"
                autocomplete="tel"
            /></el-form-item>
            <el-form-item label="完整收货地址"
              ><el-input
                v-model="address.detail"
                aria-label="完整收货地址"
                type="textarea"
                maxlength="300"
                autocomplete="street-address"
            /></el-form-item>
          </el-form>
          <p>成单后此页不能修改收货信息；重复提交不同地址会被拒绝。</p>
          <el-button
            type="primary"
            :disabled="
              busy ||
              (!pending &&
                (plan.status !== 'PENDING_CONFIRM' ||
                  plan.currentCheck?.status !== 'READY'))
            "
            @click="confirm"
            >{{ pending ? "重试本次确认" : "确认建立订单" }}</el-button
          >
        </section>
      </template>
      <RouterLink :to="'/purchase-orders?requirementId=' + plan.requirementId"
        >查看此需求已有订单 →</RouterLink
      >
    </template>
  </div>
</template>
<style scoped>
.checkout-page {
  padding-bottom: 64px;
  max-width: 960px;
}
.order-card {
  padding: 28px;
  background: white;
  border: 1px solid #e4e9e2;
  border-radius: 12px;
  margin: 24px 0;
  overflow-wrap: anywhere;
}
.order-card p {
  line-height: 1.7;
  margin: 10px 0;
}
@media (max-width: 600px) {
  .order-card {
    padding: 20px;
  }
}
</style>
