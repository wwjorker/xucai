<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import LoginForm from "../components/LoginForm.vue";
import Icon from "../components/Icon.vue";
const route = useRoute(),
  router = useRouter();
const scope = computed(() =>
  route.path.includes("merchant") ? "merchant" : "buyer",
);
function entered() {
  const next = typeof route.query.next === "string" ? route.query.next : "";
  router.replace(
    scope.value === "merchant"
      ? next.startsWith("/merchant/") && !next.startsWith("/merchant/login")
        ? next
        : "/merchant/products"
      : /^\/(?:requirements|plans|purchase-orders|cart|checkout|products)(?:\/|\?|$)/.test(next)
        ? next
        : "/",
  );
}
</script>
<template>
  <section class="login-page">
    <div class="login-story">
      <div class="eyebrow">XUCAI / WORKSPACE PROCUREMENT</div>
      <h1>
        {{
          scope === "merchant"
            ? "让每一件好设备，\n拥有清楚的档案。"
            : "从了解设备，\n开始一次好选择。"
        }}
      </h1>
      <p>
        {{
          scope === "merchant"
            ? "维护商品与来源资料，为客户核实采购方案，跟进订单与售后。"
            : "提出采购需求，对比有依据的设备方案，确认订单并跟进收货。"
        }}
      </p>
      <div class="story-grid">
        <Icon name="monitor" :size="82" /><Icon
          name="keyboard"
          :size="82"
        /><Icon name="mouse" :size="68" />
      </div>
      <span class="story-bottom">清楚的规格 · 可查的资料 · 明确的售卖状态</span>
    </div>
    <div class="login-panel">
      <RouterLink to="/" class="back-link"
        ><Icon name="back" :size="17" /> 返回设备选购</RouterLink
      >
      <div class="eyebrow">
        {{ scope === "merchant" ? "MERCHANT ACCESS" : "CUSTOMER ACCESS" }}
      </div>
      <h2>{{ scope === "merchant" ? "商家登录" : "欢迎回来" }}</h2>
      <p class="muted">
        {{
          scope === "merchant"
            ? "登录后管理商品、采购方案与订单履约。"
            : "登录你的客户账户。设备目录也可直接浏览。"
        }}
      </p>
      <LoginForm :scope="scope" @success="entered" /><RouterLink
        :to="scope === 'merchant' ? '/login' : '/merchant/login'"
        class="switch-login"
        >切换到{{ scope === "merchant" ? "客户" : "商家" }}登录
        <Icon name="arrow" :size="16"
      /></RouterLink>
    </div>
  </section>
</template>
