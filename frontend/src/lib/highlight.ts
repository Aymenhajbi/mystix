/**
 * Line-by-line syntax highlighting for the file viewer, as in the Cockpit mockup.
 * Each line is HTML-escaped FIRST, then wrapped in spans: the output only contains markup we add.
 */
export type CodeLanguage = "xml" | "json";

const escapeHtml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export function highlightLine(line: string, language: CodeLanguage): string {
  let s = escapeHtml(line);
  if (language === "xml") {
    s = s
      .replace(/(\s)([\w:.-]+)(=)("[^"]*")/g, '$1<span class="at">$2</span>$3<span class="st">$4</span>')
      .replace(/(&lt;\?[\w-]+)/g, '<span class="cm">$1</span>')
      .replace(/(&lt;\/?)([\w:.-]+)/g, '$1<span class="tg">$2</span>');
  } else {
    s = s
      .replace(/("(?:[^"\\]|\\.)*")(\s*:)/g, '<span class="at">$1</span>$2')
      .replace(/(:\s*)("(?:[^"\\]|\\.)*")/g, '$1<span class="st">$2</span>')
      .replace(/(:\s*)(-?\d+(?:\.\d+)?|null|true|false)\b/g, '$1<span class="nm">$2</span>');
  }
  return s;
}

/**
 * Shortens the namespace-qualified XPath produced by the EN 16931 Schematron:
 * /*:Invoice[namespace-uri()='…'][1]/*:InvoiceLine[namespace-uri()='…'][3] → /Invoice/InvoiceLine[3]
 */
export function readableXPath(location: string) {
  return location
    .replace(/\*:([\w.-]+)\[namespace-uri\(\)='[^']*'\]/g, "$1")
    .replace(/\[1\]/g, "");
}

/**
 * Re-indents compact JSON for reading WITHOUT parsing it: every literal is kept byte for byte
 * (JSON.parse would turn 125.50 into 125.5 and misrepresent the stored artefact).
 * Text that already spans several lines is returned unchanged.
 */
export function prettyJson(text: string) {
  if (text.includes("\n")) return text;
  let out = "";
  let depth = 0;
  let inString = false;
  const newline = () => "\n" + "  ".repeat(depth);
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      out += c;
      if (c === "\\") out += text[++i] ?? "";
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') {
      inString = true;
      out += c;
    } else if (c === "{" || c === "[") {
      const close = c === "{" ? "}" : "]";
      if (text[i + 1] === close) {
        out += c + close;
        i++;
      } else {
        depth++;
        out += c + newline();
      }
    } else if (c === "}" || c === "]") {
      depth--;
      out += newline() + c;
    } else if (c === ",") {
      out += c + newline();
    } else if (c === ":") {
      out += ": ";
    } else if (c.trim() !== "") {
      out += c;
    }
  }
  return out;
}
