<script setup>
import { reactive, ref } from "vue";
import { login } from "../api";
const props = defineProps({
  scope: { default: "buyer" },
  expectedUserId: { default: null },
});
const emit = defineEmits(["success"]);
const form = reactive({ username: "", password: "" });
const busy = ref(false),
  error = ref("");
async function submit() {
  if (!form.username.trim() || !form.password) {
    error.value = "请输入账号与密码";
    return;
  }
  error.value = "";
  busy.value = true;
  try {
    await login(
      props.scope,
      {
        username: form.username.trim(),
        password: form.password,
      },
      props.expectedUserId,
    );
    form.password = "";
    emit("success");
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <form class="login-form" @submit.prevent="submit">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <label
      >账号<el-input
        v-model="form.username"
        autocomplete="username"
        placeholder="请输入账号"
        aria-label="账号"
        size="large"
    /></label>
    <label
      >密码<el-input
        v-model="form.password"
        autocomplete="current-password"
        type="password"
        show-password
        placeholder="请输入密码"
        aria-label="密码"
        size="large"
    /></label>
    <el-button
      type="primary"
      size="large"
      native-type="submit"
      :loading="busy"
      class="full-width"
      >登录{{ scope === "merchant" ? "商家工作台" : "客户账户" }}</el-button
    >
    <p class="muted login-footnote">当前为演示环境，使用已有演示账号登录。</p>
  </form>
</template>
