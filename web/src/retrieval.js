const terminal = new Set(["DONE", "FAILED", "UNKNOWN"]);
const uuidPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

// 先保存请求再发送；刷新只能查回或重放原UUID，不能悄悄另起收费请求。
export class RetrievalSession {
  constructor({
    storage,
    owner,
    productId,
    request,
    uuid = () => crypto.randomUUID(),
  }) {
    Object.assign(this, {
      storage,
      owner: String(owner),
      productId: String(productId),
      request,
      uuid,
    });
    this.key = `xucai.retrieval.${this.owner}.${this.productId}`;
    this.ticket = null;
    this.busy = false;
    this.error = "";
    this.storageBlocked = false;
    try {
      const raw = storage.getItem(this.key);
      if (raw) {
        const t = JSON.parse(raw);
        const ids =
          t.kind === "build" ? [t.body?.productId] : t.body?.productIds;
        if (
          !["build", "search"].includes(t.kind) ||
          !uuidPattern.test(t.body?.requestId) ||
          !Array.isArray(ids) ||
          ids.length !== 1 ||
          String(ids[0]) !== this.productId ||
          t.owner !== this.owner ||
          (t.kind === "search" &&
            (typeof t.body.query !== "string" || t.body.topK !== 5))
        )
          throw new Error("恢复记录不完整，请联系维护者按原操作编号核对。");
        this.ticket = t;
      }
    } catch (e) {
      this.storageBlocked = true;
      this.error = e.message || "无法读取本地操作记录。";
    }
  }
  get pending() {
    return !!this.ticket && !terminal.has(this.ticket.response?.status);
  }
  get locked() {
    return this.busy || this.pending || this.storageBlocked;
  }
  save(expectedId = this.ticket.body.requestId) {
    try {
      const current = JSON.parse(this.storage.getItem(this.key) || "null");
      if ((current?.body?.requestId || null) !== expectedId)
        throw new Error(
          "操作记录已由另一界面更新，请重新进入页面查回，不能覆盖新请求。",
        );
      if (
        current?.body?.requestId === this.ticket.body.requestId &&
        terminal.has(current.response?.status) &&
        !terminal.has(this.ticket.response?.status)
      ) {
        this.ticket = current;
        return;
      }
      this.storage.setItem(this.key, JSON.stringify(this.ticket));
    } catch (e) {
      this.storageBlocked = true;
      throw new Error(
        e.message ||
          "无法保存操作编号，已停止新请求。请保留此页并联系维护者核对。",
      );
    }
  }
  async start(kind, query = "") {
    if (this.locked) return;
    if (!["build", "search"].includes(kind)) throw new Error("未知操作");
    if (kind === "search" && (!query.trim() || query.length > 1000)) {
      this.error = "请输入1至1000字的问题。";
      return;
    }
    this.error = "";
    const previousId = this.ticket?.body.requestId || null;
    try {
      const current = JSON.parse(this.storage.getItem(this.key) || "null");
      if ((current?.body?.requestId || null) !== previousId) {
        this.storageBlocked = true;
        this.error = "操作记录已由另一界面更新，请重新进入页面查回。";
        return;
      }
    } catch {
      this.storageBlocked = true;
      this.error = "无法读取原操作记录，已停止新请求。";
      return;
    }
    this.ticket = {
      owner: this.owner,
      kind,
      body: {
        requestId: this.uuid(),
        ...(kind === "build"
          ? { productId: this.productId }
          : { productIds: [this.productId], query, topK: 5 }),
      },
      response: null,
    };
    try {
      this.save(previousId);
    } catch (e) {
      this.error = e.message;
      return;
    }
    await this.send();
  }
  async act(readOnly) {
    if (!this.ticket || this.busy || (!readOnly && this.storageBlocked)) return;
    this.busy = true;
    this.error = "";
    const t = this.ticket;
    try {
      const response = await this.request(
        readOnly
          ? `/ai/admin/retrieval/operations/${t.body.requestId}`
          : `/ai/admin/retrieval/${t.kind}`,
        readOnly
          ? {}
          : { method: "POST", body: JSON.parse(JSON.stringify(t.body)) },
      );
      if (
        response?.id !== t.body.requestId ||
        !["RUNNING", ...terminal].includes(response.status)
      )
        throw new Error("返回结果无法与原请求对应，请按原编号查回。");
      t.response = response;
      this.save();
    } catch (e) {
      this.error = e.message || "结果未知，请查回原操作。";
    } finally {
      this.busy = false;
    }
  }
  async send() {
    await this.act(false);
  }
  async recover() {
    await this.act(true);
  }
}

export function retrievalLabel(status) {
  return (
    {
      READY: "索引已就绪",
      NOT_BUILT: "尚未建立索引",
      STALE: "资料已变化，需要重建索引",
      INCOMPLETE: "索引不完整，需要重建",
      SEARCHED: "检索完成",
      PARTIAL: "部分片段未通过复核",
      RUNNING: "处理中，请稍后查回",
      FAILED: "本次操作未完成",
      UNKNOWN: "上次进程中断，结果未确认",
    }[status] ||
    status ||
    "等待操作"
  );
}

export function locatedHitError(chunk, located, productId) {
  if (
    !located?.current ||
    !located?.located ||
    located.indexState !== "READY" ||
    located.productStatus !== "PUBLISHED"
  )
    return "资料已变化或无法定位，请重新读取资料。";
  if (
    ["productId", "docId", "version", "ruleVersion", "chunkId"].some(
      (k) => String(located[k]) !== String(chunk[k]),
    ) ||
    String(chunk.productId) !== String(productId) ||
    located.charStart !== chunk.charStart ||
    located.charEnd !== chunk.charEnd ||
    located.text !== chunk.text ||
    typeof located.content !== "string" ||
    located.content.slice(chunk.charStart, chunk.charEnd) !== chunk.text
  )
    return "命中与当前原文不一致，不能采用。";
  return "";
}
