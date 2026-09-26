// Reject stale reads, and never let an older request release a newer write.
export function createAssistantRequestGate() {
  let version = 0, write = null;
  return {
    get writing() { return write !== null; },
    read() { return write === null ? ++version : null; },
    current(ticket) { return ticket !== null && write === null && ticket === version; },
    begin() { if (write !== null) return null; write = ++version; return write; },
    end(ticket) { if (ticket !== null && ticket === write) write = null; },
  };
}
