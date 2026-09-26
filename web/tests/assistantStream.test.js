import test from "node:test";
import assert from "node:assert/strict";
import { readAssistantEvents } from "../src/assistantStream.js";

test("SSE keeps Chinese across byte boundaries and delivers incremental snapshots", async () => {
  const bytes = new TextEncoder().encode('event:message\r\ndata:{"status":"RUNNING","text":"我是序采"}\r\n\r\ndata:{"status":"DONE","text":""}\n\n');
  const events = [];
  const body = new ReadableStream({ start(c) { for (let i = 0; i < bytes.length; i += 2) c.enqueue(bytes.slice(i, i + 2)); c.close(); } });
  await readAssistantEvents(body, (frame) => events.push(frame));
  assert.equal(events.length, 2); assert.equal(events[0].text, "我是序采"); assert.equal(events[1].status, "DONE");
});
test("truncated SSE never claims a complete response", async () => {
  const body = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('data:{"status":"RUNNING"')); c.close(); } });
  await assert.rejects(readAssistantEvents(body, () => {}), /中断/);
});
test("text is delivered while the response body is still open", async () => {
  let controller;
  const events = [], body = new ReadableStream({ start(c) { controller = c; } });
  const reading = readAssistantEvents(body, (frame) => events.push(frame));
  controller.enqueue(new TextEncoder().encode('data:{"status":"RUNNING","text":"第一段"}\n\n'));
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(events, [{ status: "RUNNING", text: "第一段" }]);
  controller.enqueue(new TextEncoder().encode('data:{"status":"DONE","text":""}\n\n'));
  controller.close(); await reading;
  assert.equal(events[1].status, "DONE");
});


test("terminal frame releases UI without waiting for transport close", async () => {
  let canceled = false;
  const body = new ReadableStream({
    start(c) { c.enqueue(new TextEncoder().encode('data:{"status":"DONE","text":""}\n\n')); },
    cancel() { canceled = true; },
  });
  const frames = [];
  await readAssistantEvents(body, f => frames.push(f));
  assert.equal(frames[0].status, "DONE");
  assert.equal(canceled, true);
});
