({
  // Words of buttons that close a layer without doing anything else. Scores: close 3, accept 3, soft 2.
  CLOSE: ["close", "dismiss", "×", "✕", "✖", "x", "kapat", "schließen", "schliessen", "закрыть", "閉じる", "إغلاق", "اغلاق"],
  ACCEPT: ["accept", "accept all", "allow all", "agree", "i agree", "ok", "okay", "got it", "i understand", "understood",
           "kabul et", "kabul", "tümünü kabul et", "tamam", "anladım", "onayla",
           "akzeptieren", "alle akzeptieren", "zustimmen", "verstanden",
           "принять", "принять все", "согласен", "понятно", "ок",
           "同意", "同意する", "了解", "すべて許可", "قبول", "موافق", "حسنا", "فهمت"],
  SOFT: ["no thanks", "no, thanks", "not now", "maybe later", "later", "skip", "continue", "remind me later",
         "hayır teşekkürler", "şimdi değil", "daha sonra", "atla", "devam",
         "nein danke", "später", "überspringen", "weiter",
         "нет, спасибо", "не сейчас", "позже", "пропустить",
         "結構です", "後で", "スキップ", "لا شكرا", "لاحقا", "تخطي"],
  // Never clicked: these change data or spend money.
  DANGER: /(delete|remove|buy|pay|order|purchase|checkout|subscribe|sign ?up|register|submit|send|confirm|sil|satın|öde|sipariş|abone|kaydol|gönder|löschen|kaufen|bezahlen|bestellen|abonnieren|удалить|купить|оплатить|заказать|подписаться|削除|購入|支払|注文|登録|حذف|شراء|ادفع|اشترك)/i,
  HINT_ATTR: /(close|dismiss|accept|consent|agree|cookie-?accept|modal-?close|btn-?close)/i,

  norm(s) {
    return String(s || "").replace(/\s+/g, " ").trim().toLowerCase();
  },

  visible(el) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const st = getComputedStyle(el);
    return st.visibility !== "hidden" && st.display !== "none" && st.opacity !== "0";
  },

  describe(el) {
    let s = el.tagName.toLowerCase();
    if (el.id) s += "#" + el.id;
    const role = el.getAttribute("role");
    if (role) s += "[role=" + role + "]";
    const cls = (el.getAttribute("class") || "").trim().split(/\s+/).slice(0, 2).join(".");
    if (cls) s += "." + cls;
    const text = this.norm(el.innerText).slice(0, 60);
    return text ? s + ' "' + text + '"' : s;
  },

  /** The element covering the target's center, or null when the target can receive the action. */
  cover(el) {
    if (!this.visible(el)) return null;
    el.scrollIntoView({ block: "center", inline: "nearest" });
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + Math.min(r.height / 2, 20);
    if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) return null;
    const top = document.elementFromPoint(x, y);
    if (!top || top === el || el.contains(top) || top.contains(el)) return null;
    if (top.closest("[data-healer-ui]")) return null;
    if (el.labels && [...el.labels].some(l => l.contains(top))) return null;   // a styled label over its input
    return top;
  },

  /** The outermost layer the covering element belongs to: a dialog, or a fixed / sticky container. */
  layer(top) {
    let layer = null;
    for (let n = top; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
      const st = getComputedStyle(n);
      if (n.tagName === "DIALOG" || n.getAttribute("role") === "dialog" || n.getAttribute("role") === "alertdialog"
          || n.getAttribute("aria-modal") === "true" || st.position === "fixed" || st.position === "sticky") layer = n;
    }
    return layer;
  },

  score(btn) {
    const text = this.norm(btn.innerText || btn.value);
    const label = this.norm(btn.getAttribute("aria-label") || btn.getAttribute("title"));
    const words = [text, label].filter(Boolean);
    if (words.some(w => this.DANGER.test(w))) return 0;
    let s = 0;
    for (const w of words) {
      if (this.CLOSE.includes(w)) s = Math.max(s, 3);
      else if (this.ACCEPT.includes(w)) s = Math.max(s, 3);
      else if (this.SOFT.includes(w)) s = Math.max(s, 2);
      else if (this.CLOSE.concat(this.ACCEPT).some(k => k.length > 2 && w.split(" ").length <= 4 && w.includes(k))) s = Math.max(s, 1.5);
    }
    const attrs = [btn.id, btn.getAttribute("class"), btn.getAttribute("data-testid"), btn.getAttribute("name")].join(" ");
    if (this.HINT_ATTR.test(attrs)) s += 1.5;
    if (!text && s > 0) s += 0.2;   // icon-only close button
    return s;
  },

  /** The best closing button of the layer, never one that contains the target. */
  choose(layer, el) {
    let best = null, bestScore = 0;
    for (const b of layer.querySelectorAll("button, a, [role=button], input[type=button], input[type=submit], [aria-label]")) {
      if (!this.visible(b) || b.disabled || b.contains(el)) continue;
      const s = this.score(b);
      if (s > bestScore) { best = b; bestScore = s; }
    }
    return best ? { el: best, score: bestScore } : null;
  },

  /**
   * Inspects the target: null if nothing covers it, otherwise the covering layer and the button that closes
   * it (null: the caller tries Escape). selectorOf builds selectors for the report.
   */
  inspect(el, selectorOf) {
    const top = this.cover(el);
    if (!top) return null;
    const layer = this.layer(top) || top;
    const best = this.choose(layer, el);
    return {
      layer: this.describe(layer),
      layerSelector: selectorOf(layer),
      button: best ? this.describe(best.el) : null,
      buttonSelector: best ? selectorOf(best.el) : null,
      score: best ? best.score : 0
    };
  },

  /** The closing button as an element handle for the click. */
  button(el) {
    const top = this.cover(el);
    if (!top) return null;
    const best = this.choose(this.layer(top) || top, el);
    return best ? best.el : null;
  }
})
