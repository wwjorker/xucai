import { reactive } from "vue";

const readToken = (scope) => {
  try {
    return sessionStorage.getItem("xucai." + scope) || "";
  } catch {
    return "";
  }
};
export const sessions = reactive({
  buyer: { token: readToken("buyer"), user: null },
  merchant: { token: readToken("merchant"), user: null },
});
export const authPrompt = reactive({ scope: null, expectedUserId: null });
const lastUsers = {};
function promptLogin(scope) {
  authPrompt.expectedUserId =
    sessions[scope].user?.id ?? lastUsers[scope] ?? null;
  authPrompt.scope = scope;
}
const refreshing = {};
export class ApiError extends Error {
  constructor(message, code, requestId) {
    super(message);
    this.code = code;
    this.requestId = requestId;
  }
}
function setToken(scope, token) {
  sessions[scope].token = token;
  try {
    token
      ? sessionStorage.setItem("xucai." + scope, token)
      : sessionStorage.removeItem("xucai." + scope);
  } catch {
    /* 禁用存储时保留本页会话 */
  }
}
export function clearSession(scope) {
  setToken(scope, "");
  sessions[scope].user = null;
}
async function transport(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const headers = { Accept: options.binary ? "image/png,image/jpeg" : "application/json" };
    const multipart = options.body instanceof FormData;
    if (options.body !== undefined && !multipart)
      headers["Content-Type"] = "application/json";
    const token =
      options.token ?? (options.scope && sessions[options.scope].token);
    if (token) headers.authorization = token;
    const response = await fetch("/api" + path, {
      method: options.method || "GET",
      credentials: "same-origin",
      headers,
      body:
        options.body === undefined ? undefined : multipart ? options.body : JSON.stringify(options.body),
      signal: controller.signal,
    });
    if (options.binary && response.ok && /^image\/(png|jpeg)(;|$)/i.test(response.headers.get("Content-Type") || "")) return await response.blob();
    let data;
    try {
      data = await response.json();
    } catch {
      throw new ApiError("服务暂不可用，请稍后重试", response.status || 503);
    }
    if (!response.ok || data.code !== 200)
      throw new ApiError(
        data.msg || "请求未完成，请重试",
        response.status === 401 ? 401 : data.code || response.status,
        data.requestId,
      );
    return data.data ?? null;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(
      error.name === "AbortError"
        ? "请求超时，结果可能已保存。请刷新核对后再操作"
        : "无法连接服务，请检查连接后重试",
      503,
    );
  } finally {
    clearTimeout(timer);
  }
}
export async function refreshSession(scope) {
  if (!refreshing[scope]) {
    refreshing[scope] = transport("/auth/accounts/refresh?client=" + scope)
      .then(async (token) => {
        const user = await transport("/users/users/me", { token });
        const expected = sessions[scope].user?.id ?? lastUsers[scope];
        if (expected != null && String(user.id) !== String(expected))
          throw new ApiError("账户已变化，请使用原账户恢复编辑", 401);
        setToken(scope, token);
        return token;
      })
      .catch((error) => {
        clearSession(scope);
        throw error;
      })
      .finally(() => {
        delete refreshing[scope];
      });
  }
  return refreshing[scope];
}
export async function api(path, options = {}) {
  try {
    return await transport(path, options);
  } catch (error) {
    if (error.code === 401 && options.scope && options.retry !== false) {
      try {
        await refreshSession(options.scope);
      } catch {
        if (options.prompt !== false) promptLogin(options.scope);
        throw new ApiError("登录已过期，重新登录后可继续；当前输入已保留", 401);
      }
      try {
        return await transport(path, options);
      } catch (again) {
        if (again.code === 401) {
          clearSession(options.scope);
          if (options.prompt !== false) promptLogin(options.scope);
        }
        throw again;
      }
    }
    throw error;
  }
}
export async function ensureSession(scope) {
  try {
    if (!sessions[scope].token) await refreshSession(scope);
    const user = await api("/users/users/me", { scope, prompt: false });
    sessions[scope].user = user;
    lastUsers[scope] = user.id;
    return true;
  } catch {
    clearSession(scope);
    return false;
  }
}
export async function login(scope, form, expectedUserId = null) {
  clearSession(scope);
  const token = await transport(
    "/auth/accounts/" + (scope === "merchant" ? "admin/login" : "login"),
    { method: "POST", body: { ...form, type: 1, rememberMe: false } },
  );
  try {
    const user = await transport("/users/users/me", { token });
    if (expectedUserId != null && String(user.id) !== String(expectedUserId)) {
      await transport("/auth/accounts/logout", { token, method: "POST" });
      throw new ApiError(
        "请使用原账户恢复编辑。切换账户请先离开当前页面。",
        403,
      );
    }
    setToken(scope, token);
    sessions[scope].user = user;
    lastUsers[scope] = user.id;
    return sessions[scope].user;
  } catch (error) {
    clearSession(scope);
    throw error;
  }
}
export async function logout(scope) {
  // 服务端失败时不要谎称refresh已撤销，页面会显示可重试的错误。
  await api("/auth/accounts/logout", { scope, method: "POST" });
  clearSession(scope);
  delete lastUsers[scope];
}
export const query = (values) =>
  new URLSearchParams(
    Object.entries(values).filter(([, v]) => v !== "" && v != null),
  ).toString();
