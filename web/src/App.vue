<script setup>
import { computed, onMounted } from "vue";
import {
  useRoute,
  useRouter,
  isNavigationFailure,
  NavigationFailureType,
} from "vue-router";
import { ElMessage } from "element-plus";
import { sessions, ensureSession, logout, authPrompt } from "./api";
import Icon from "./components/Icon.vue";
import LoginForm from "./components/LoginForm.vue";
import ProcurementAssistant from "./components/ProcurementAssistant.vue";
const route = useRoute(),
  router = useRouter();
const merchant = computed(() => route.path.startsWith("/merchant"));
const scope = computed(() => (merchant.value ? "merchant" : "buyer"));
const current = computed(() => sessions[scope.value]);
onMounted(() => ensureSession("buyer"));
async function signOut() {
  try {
    const leavingScope = scope.value;
    const canceled = await router.push(
      leavingScope === "merchant" ? "/merchant/login" : "/login",
    );
    if (
      isNavigationFailure(
        canceled,
        NavigationFailureType.aborted | NavigationFailureType.cancelled,
      )
    )
      return;
    await logout(leavingScope);
    ElMessage.success("已退出当前账户");
  } catch (e) {
    ElMessage.error(e.message);
  }
}
</script>
<template>
  <RouterView v-if="route.meta.standalone" />
  <div v-else :class="['app', { 'merchant-app': merchant }]">
    <header class="site-header">
      <div class="header-inner">
        <RouterLink :to="merchant ? '/merchant/products' : '/'" class="brand"
          ><span class="brand-mark">序</span
          ><span
            >序采<small>{{
              merchant ? "商家工作台" : "办公设备选购"
            }}</small></span
          ></RouterLink
        >
        <nav class="header-nav" aria-label="主导航">
          <RouterLink v-if="!merchant" to="/cart">采购清单</RouterLink>
          <RouterLink
            v-if="!merchant"
            to="/"
            :class="{
              active: route.path === '/' || route.path.startsWith('/products'),
            }"
            >设备选购</RouterLink
          ><RouterLink
            v-if="merchant"
            to="/merchant/products"
            :class="{ active: route.path.startsWith('/merchant/products') }"
            >商品管理</RouterLink
          ><RouterLink
            v-if="merchant"
            to="/merchant/doc-chunks"
            :class="{ active: route.path.startsWith('/merchant/doc-chunks') }"
            >资料片段</RouterLink
          >
          <RouterLink
            v-if="merchant"
            to="/merchant/retrieval"
            :class="{ active: route.path === '/merchant/retrieval' }"
            >语义检索</RouterLink
          >
          <RouterLink
            :to="merchant ? '/merchant/requirements' : '/requirements'"
            :class="{ active: route.path.includes('/requirements') }"
            >{{ merchant ? "客户需求" : "我的采购需求" }}</RouterLink
          >
          <RouterLink
            :to="merchant ? '/merchant/purchase-orders' : '/purchase-orders'"
            :class="{ active: route.path.includes('/purchase-orders') }"
            >采购订单</RouterLink
          >
        </nav>
        <div class="header-actions">
          <span class="demo-badge">演示环境</span>
          <RouterLink
            :to="merchant ? '/' : '/merchant/products'"
            class="portal-link"
            >{{ merchant ? "查看客户站" : "商家工作台" }}
            <Icon name="link" :size="15"
          /></RouterLink>
          <template v-if="current.user"
            ><span class="account-name"
              ><Icon name="user" :size="17" />{{
                current.user.name || current.user.username
              }}</span
            ><button
              class="icon-button"
              aria-label="退出当前账户"
              title="退出当前账户"
              @click="signOut"
            >
              <Icon name="logout" /></button
          ></template>
          <RouterLink
            v-else
            :to="merchant ? '/merchant/login' : '/login'"
            class="login-link"
            >登录 <Icon name="arrow" :size="15"
          /></RouterLink>
        </div>
      </div>
    </header>
    <main><RouterView :key="route.path" /></main>
    <ProcurementAssistant
      v-if="!merchant && sessions.buyer.user"
      :key="String(sessions.buyer.user.id)"
    />
    <footer class="site-footer">
      <span>序采 <span class="footer-dot">·</span> 办公设备选购与采购平台</span
      ><RouterLink to="/guide">采购流程与使用说明</RouterLink
      ><span>商品参数以来源资料为准；价格与库存仅供演示。</span>
    </footer>
    <el-dialog
      :model-value="!!authPrompt.scope"
      title="重新登录，继续编辑"
      width="420px"
      :close-on-click-modal="false"
      @close="authPrompt.scope = null"
    >
      <p class="muted">
        登录已过期，当前页面输入仍保留。登录后请重新提交原操作。
      </p>
      <LoginForm
        v-if="authPrompt.scope"
        :scope="authPrompt.scope"
        :expected-user-id="authPrompt.expectedUserId"
        @success="authPrompt.scope = null"
      />
    </el-dialog>
  </div>
</template>
