// Deliberately limited Markdown: Vue renders all labels as text, never HTML.
export function assistantTextParts(value = "") {
  return value.split(/(\[[^\]\n]+\]\(https?:\/\/[^\s<>]+\)|\*\*[^*\n]+\*\*|`[^`\n]+`)/g).map((text) => {
    const link = text.match(/^\[([^\]\n]+)\]\((https?:\/\/[^\s<>]+)\)$/);
    if (link) {
      try {
        const url = new URL(link[2]);
        if (["http:", "https:"].includes(url.protocol) && !url.username && !url.password)
          return { text: link[1], href: url.href };
      } catch { /* Malformed links remain plain text. */ }
    }
    if (text.startsWith("**") && text.endsWith("**")) return { text: text.slice(2, -2), bold: true };
    if (text.startsWith("`") && text.endsWith("`")) return { text: text.slice(1, -1), code: true };
    return { text };
  });
}
