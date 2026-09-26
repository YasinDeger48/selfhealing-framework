({
  // Renames developers typically make. Used for identifier tokens and visible text; anything else gets a generic change.
  WORDS: { login: "signin", signin: "login", username: "user", user: "account", password: "passcode", submit: "send",
    send: "submit", button: "btn", btn: "button", input: "field", field: "input", search: "find", cart: "basket",
    basket: "cart", email: "mail", product: "item", item: "product", select: "dropdown", checkout: "purchase",
    filter: "refine", category: "group", message: "note", phone: "tel", count: "badge", close: "dismiss", save: "store",
    next: "continue", back: "previous", add: "put", remove: "delete", apply: "redeem", code: "voucher", coupon: "promo",
    nav: "menu", link: "anchor", logout: "signout", sort: "order", qty: "quantity", quantity: "amount", size: "variant" },
  TEXTS: { "sign in": "Log in", "log in": "Sign in", "login": "Sign in", "submit": "Send", "send": "Submit",
    "search": "Find", "add to cart": "Add to basket", "checkout": "Proceed to checkout", "save": "Save changes",
    "cancel": "Dismiss", "continue": "Next", "next": "Continue", "apply": "Redeem", "logout": "Sign out",
    "details": "View details", "place order": "Complete purchase", "send message": "Submit message" },
  TARGETS: "input:not([type=hidden]), button, a[href], select, textarea, [role=button]",

  rng(seed) {
    let a = seed >>> 0;
    return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  },

  visible(el) {
    const r = el.getBoundingClientRect();
    const st = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && st.visibility !== "hidden" && st.display !== "none";
  },

  /** Marks up to max interactive elements with data-bench-id (invisible to healing) and returns their selectors. */
  mark(max, selectorOf) {
    const out = [];
    for (const el of document.querySelectorAll(this.TARGETS)) {
      if (out.length >= max) break;
      if (!this.visible(el) || el.closest("[data-healer-ui]")) continue;
      const selector = selectorOf(el);
      if (!selector) continue;
      el.setAttribute("data-bench-id", String(out.length));
      out.push({ id: out.length, selector, tag: el.tagName.toLowerCase() });
    }
    return out;
  },

  renameIdentifier(v, r) {
    const parts = v.split(/([-_])/);
    for (let i = 0; i < parts.length; i += 2) {
      const w = this.WORDS[parts[i].toLowerCase()];
      if (w && r() < 0.8) { parts[i] = w; return parts.join(""); }
    }
    const tokens = v.split(/[-_]/);
    return tokens.length > 1 && r() < 0.5 ? tokens.reverse().join("-") : v + "-el";
  },

  changeText(el, r) {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const t = n.textContent.trim();
      if (!t) continue;
      const mapped = this.TEXTS[t.toLowerCase()];
      n.textContent = n.textContent.replace(t, mapped || (r() < 0.5 ? t + " now" : t.toUpperCase()));
      return;
    }
  },

  retag(el) {
    if (el.tagName !== "BUTTON") return el;
    const a = document.createElement("a");
    for (const at of el.attributes) if (at.name !== "type") a.setAttribute(at.name, at.value);
    a.setAttribute("role", "button");
    a.setAttribute("href", "#");
    while (el.firstChild) a.appendChild(el.firstChild);
    el.replaceWith(a);
    return a;
  },

  /**
   * Applies one release worth of changes to every marked element. Levels are cumulative:
   * low - ids, test ids, classes renamed; medium - also name, placeholder, text, an extra wrapper;
   * high - also one identifier dropped, buttons re-tagged as links, moved among siblings;
   * extreme - all identifiers (id, data-testid, name) and classes dropped, text changed;
   * removed - 40% of the elements deleted (they must NOT be healed onto something else).
   */
  mutate(level, seed) {
    const r = this.rng(seed);
    const order = ["low", "medium", "high", "extreme"];
    const lv = order.indexOf(level);
    const removed = [];
    for (const original of [...document.querySelectorAll("[data-bench-id]")]) {
      let el = original;
      if (level === "removed") {
        if (r() < 0.4) { removed.push(Number(el.dataset.benchId)); el.remove(); }
        continue;
      }
      if (lv >= 0) {
        for (const a of ["id", "data-testid", "data-test", "data-qa", "data-cy"]) {
          const v = el.getAttribute(a);
          if (v) el.setAttribute(a, this.renameIdentifier(v, r));
        }
        const cls = (el.getAttribute("class") || "").split(/\s+/).filter(Boolean);
        if (cls.length) { const i = Math.floor(r() * cls.length); cls[i] = this.renameIdentifier(cls[i], r); el.setAttribute("class", cls.join(" ")); }
      }
      if (lv >= 1) {
        const name = el.getAttribute("name");
        if (name) el.setAttribute("name", this.renameIdentifier(name, r));
        const ph = el.getAttribute("placeholder");
        if (ph) el.setAttribute("placeholder", this.TEXTS[ph.toLowerCase()] || ph + "...");
        if (!["INPUT", "SELECT", "TEXTAREA"].includes(el.tagName)) this.changeText(el, r);
        const wrap = document.createElement("div");
        wrap.className = "wrapper-" + Math.floor(r() * 1000);
        el.replaceWith(wrap);
        wrap.appendChild(el);
      }
      if (lv >= 2) {
        const ids = ["id", "data-testid", "name"].filter(a => el.hasAttribute(a));
        if (ids.length > 1) el.removeAttribute(ids[Math.floor(r() * ids.length)]);
        el = this.retag(el);
        const box = el.parentElement;
        if (box && box.previousElementSibling && r() < 0.5) box.parentElement.insertBefore(box, box.previousElementSibling);
      }
      if (lv >= 3) {
        for (const a of ["id", "data-testid", "data-test", "data-qa", "data-cy", "name", "class"]) el.removeAttribute(a);
      }
    }
    return removed;
  }
})
