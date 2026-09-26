// Provider deltas arrive through authenticated SSE. No message is re-sent on a stream failure.
export async function readAssistantEvents(body, onFrame) {
  if (!body) throw new Error("流式响应不可用");
  const reader = body.getReader(), decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      if (buffer.length > 1000000) throw new Error("流式响应过长");
      let boundary;
      while ((boundary = buffer.indexOf("\n\n")) !== -1) {
        const event = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
        const data = event.split("\n").filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n");
        if (data) {
          const frame = JSON.parse(data);
          onFrame(frame);
          if (["DONE", "FAILED", "CANCELED"].includes(frame.status)) {
            await reader.cancel();
            return;
          }
        }
      }
      if (done) break;
    }
    if (buffer.trim()) throw new Error("流式响应中断");
  } finally { reader.releaseLock(); }
}
