({
  panel() {
    let p = document.getElementById("__healer_panel");
    if (p) return p;
    p = document.createElement("div");
    p.id = "__healer_panel";
    p.setAttribute("data-healer-ui", "");
    p.style.cssText = "position:fixed;right:16px;bottom:16px;width:460px;max-height:60vh;overflow:auto;" +
      "background:rgba(17,24,39,.94);color:#e5e7eb;font:13px/1.45 Consolas,monospace;border-radius:10px;" +
      "box-shadow:0 8px 30px rgba(0,0,0,.35);z-index:2147483647;pointer-events:none;padding:12px 14px";
    p.innerHTML = '<div style="font-weight:700;color:#fff;margin-bottom:6px">Self-Healing</div>';
    document.body.appendChild(p);
    return p;
  },

  log(html, color) {
    const p = this.panel();
    const line = document.createElement("div");
    line.style.cssText = "margin:3px 0;color:" + (color || "#e5e7eb");
    line.innerHTML = html;
    p.appendChild(line);
    p.scrollTop = p.scrollHeight;
  },

  clearBoxes() {
    document.querySelectorAll("[data-healer-box]").forEach(b => b.remove());
  },

  /** Finds an element like Playwright does: open shadow roots are pierced, "host >> inner" is supported. */
  find(selector) {
    const deep = (root, sel) => {
      try {
        const hit = root.querySelector(sel);
        if (hit) return hit;
      } catch (e) { return null; }
      for (const el of root.querySelectorAll("*")) {
        if (el.shadowRoot) { const hit = deep(el.shadowRoot, sel); if (hit) return hit; }
      }
      return null;
    };
    const parts = selector.split(" >> ");
    let scope = document;
    for (let i = 0; i < parts.length; i++) {
      const el = deep(scope, parts[i]);
      if (!el) return null;
      if (i === parts.length - 1) return el;
      scope = el.shadowRoot || el;
    }
    return null;
  },

  box(selector, label, color, solid) {
    const el = this.find(selector);
    if (!el) return;
    el.scrollIntoView({ block: "center", inline: "nearest" });
    const r = el.getBoundingClientRect();
    const b = document.createElement("div");
    b.setAttribute("data-healer-ui", "");
    b.setAttribute("data-healer-box", "");
    b.style.cssText = "position:absolute;z-index:2147483646;pointer-events:none;border-radius:6px;" +
      "left:" + (r.left + scrollX - 4) + "px;top:" + (r.top + scrollY - 4) + "px;" +
      "width:" + (r.width + 8) + "px;height:" + (r.height + 8) + "px;" +
      "border:3px " + (solid ? "solid " : "dashed ") + color + ";" +
      "box-shadow:0 0 0 4px " + color + "33";
    const tag = document.createElement("div");
    tag.style.cssText = "position:absolute;left:-3px;top:-24px;white-space:nowrap;background:" + color +
      ";color:#fff;font:bold 12px Consolas,monospace;padding:2px 6px;border-radius:4px";
    tag.textContent = label;
    b.appendChild(tag);
    document.body.appendChild(b);
  }
})
