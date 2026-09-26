// A historical answer must never replay a later purchase instruction.
export function retryQuestion(messages, assistantId) {
  const reply = messages?.at(-1), question = messages?.at(-2);
  if (!assistantId || reply?.id !== assistantId || reply.role !== "assistant" ||
      reply.canRetry !== true || question?.role !== "user") return null;
  return typeof question.text === "string" && question.text.trim() ? question.text : null;
}
