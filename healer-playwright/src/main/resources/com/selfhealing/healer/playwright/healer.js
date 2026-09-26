({
  TRACKED: ["id", "data-testid", "data-qa", "name", "aria-label", "placeholder", "title", "type", "role", "class", "href"],
  SKIP_TAGS: new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "META", "LINK", "HEAD", "HTML", "BODY", "BR", "HR",
                      "PATH", "SVG", "G", "USE", "OPTION"]),
  MAX_CANDIDATES: 2000,

  /** The document plus every open shadow root below it (Playwright's CSS engine pierces them too). */
  roots() {
    if (this._roots) return this._roots;
    const out = [document];
    const walk = root => {
      for (const el of root.querySelectorAll("*")) {
        if (el.shadowRoot) { out.push(el.shadowRoot); walk(el.shadowRoot); }
      }
    };
    walk(document);
    this._roots = out;
    return out;
  },

  /** querySelectorAll across the document and all open shadow roots; null for an invalid selector. */
  deepAll(sel) {
    const found = [];
    try {
      for (const r of this.roots()) found.push(...r.querySelectorAll(sel));
    } catch (e) { return null; }
    return found;
  },

  /** Parent element, stepping out of a shadow root to its host. */
  parentOf(el) {
    if (el.parentElement) return el.parentElement;
    const root = el.getRootNode();
    return root instanceof ShadowRoot ? root.host : null;
  },

  text(el) {
    const tag = el.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return "";
    return (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 120);
  },

  labelText(el) {
    if (el.labels && el.labels.length) return el.labels[0].innerText.replace(/\s+/g, " ").trim().slice(0, 80);
    const labelled = el.getAttribute("aria-labelledby");
    if (labelled) {
      const ref = el.getRootNode().getElementById ? el.getRootNode().getElementById(labelled) : null;
      if (ref) return ref.innerText.trim().slice(0, 80);
    }
    const wrap = el.closest("label");
    if (wrap) return wrap.innerText.replace(/\s+/g, " ").trim().slice(0, 80);
    // A renamed id breaks <label for="...">; the label usually still sits right before the field.
    const prev = el.previousElementSibling;
    if (prev && prev.tagName === "LABEL") return prev.innerText.replace(/\s+/g, " ").trim().slice(0, 80);
    return "";
  },

  describeAncestor(el) {
    let s = el.tagName.toLowerCase();
    if (el.id) s += "#" + el.id;
    const tid = el.getAttribute("data-testid");
    if (tid) s += "[" + tid + "]";
    const cls = (el.getAttribute("class") || "").trim().split(/\s+/).slice(0, 2).join(".");
    if (cls) s += "." + cls;
    return s;
  },

  xpath(el) {
    if (el.getRootNode() !== document) return "";   // XPath cannot address shadow DOM
    const parts = [];
    for (let n = el; n && n.nodeType === 1 && n !== document.documentElement; n = n.parentElement) {
      let i = 1;
      for (let s = n.previousElementSibling; s; s = s.previousElementSibling) if (s.tagName === n.tagName) i++;
      parts.unshift(n.tagName.toLowerCase() + "[" + i + "]");
    }
    return "/html/" + parts.join("/");
  },

  isVisible(el) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const st = getComputedStyle(el);
    return st.visibility !== "hidden" && st.display !== "none" && st.opacity !== "0";
  },

  snapshot(el) {
    const attributes = {};
    for (const a of this.TRACKED) {
      const v = el.getAttribute(a);
      if (v !== null && v !== "") attributes[a] = v;
    }
    const ancestors = [];
    for (let p = this.parentOf(el); p && p !== document.body && ancestors.length < 4; p = this.parentOf(p)) {
      ancestors.push(this.describeAncestor(p));
    }
    const r = el.getBoundingClientRect();
    return {
      tag: el.tagName.toLowerCase(),
      attributes,
      text: this.text(el),
      labelText: this.labelText(el),
      ancestors,
      xpath: this.xpath(el),
      x: Math.round(r.x + window.scrollX), y: Math.round(r.y + window.scrollY),
      width: Math.round(r.width), height: Math.round(r.height),
      visible: this.isVisible(el)
    };
  },

  unique(sel) {
    const found = this.deepAll(sel);
    return found !== null && found.length === 1;
  },

  q(v) {
    return '"' + String(v).replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
  },

  /** Shortest stable selector that matches only this element (Playwright syntax, pierces shadow DOM). */
  uniqueSelector(el) {
    const tag = el.tagName.toLowerCase();
    const tries = [];
    const tid = el.getAttribute("data-testid");
    if (tid) tries.push("[data-testid=" + this.q(tid) + "]");
    if (el.id) tries.push("#" + CSS.escape(el.id));
    for (const a of ["data-qa", "name", "aria-label", "placeholder", "title"]) {
      const v = el.getAttribute(a);
      if (v) { tries.push("[" + a + "=" + this.q(v) + "]"); tries.push(tag + "[" + a + "=" + this.q(v) + "]"); }
    }
    for (const s of tries) if (this.unique(s)) return s;

    // Path from the nearest uniquely identifiable ancestor inside the same root.
    const root = el.getRootNode();
    const segments = [];
    for (let n = el; n && n !== document.body; n = n.parentElement) {
      let seg = n.tagName.toLowerCase();
      const siblings = n.parentElement ? n.parentElement.children : root.children;
      const same = Array.from(siblings || []).filter(c => c.tagName === n.tagName);
      if (same.length > 1) seg += ":nth-of-type(" + (same.indexOf(n) + 1) + ")";
      segments.unshift(seg);
      const p = n.parentElement;
      if (!p || p === document.body) break;
      const pid = p.getAttribute("data-testid");
      const anchor = pid ? "[data-testid=" + this.q(pid) + "]" : (p.id ? "#" + CSS.escape(p.id) : null);
      if (anchor && this.unique(anchor)) {
        const sel = anchor + " > " + segments.join(" > ");
        if (this.unique(sel)) return sel;
      }
    }
    if (root instanceof ShadowRoot) {
      // Inside a shadow root: "<host selector> >> <path inside the shadow root>".
      const host = this.uniqueSelector(root.host);
      const inner = segments.join(" > ");
      if (host && root.querySelectorAll(inner).length === 1) return host + " >> " + inner;
      return null;
    }
    const full = "body > " + segments.join(" > ");
    return this.unique(full) ? full : null;
  },

  collect() {
    this._roots = null;
    const out = [];
    for (const root of this.roots()) {
      const scope = root === document ? document.body : root;
      for (const el of scope.querySelectorAll("*")) {
        if (this.SKIP_TAGS.has(el.tagName.toUpperCase())) continue;
        if (el.closest("[data-healer-ui]")) continue; // the healer's own demo overlay
        const hasIdentity = this.TRACKED.some(a => el.hasAttribute(a));
        if (!hasIdentity && !this.isVisible(el)) continue;
        const snap = this.snapshot(el);
        snap.selector = this.uniqueSelector(el);
        if (snap.selector) out.push(snap);
        if (out.length >= this.MAX_CANDIDATES) return out;
      }
    }
    return out;
  }
})
