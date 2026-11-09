"use strict";
/* The conversation pane. Every message shown here came from the server: pages are fetched, changes arrive as live events
   and are applied by id. The only local state is what a screen needs between answers: the loaded window of each
   conversation, where it was scrolled, and messages still on their way ("enviando"), matched to the server copy by nonce. */
(function () {
  const E = AV.el;
  const PAGE = 50;
  const GROUP_MS = 7 * 60000;
  const TYPING_SEND_MS = 8000;
  const TYPING_SHOW_MS = 10000;
  const MAX_FILES = 10;
  const MAX_FILE_BYTES = 25 * 1024 * 1024;
  const MAX_IMAGE_SIDE = 2560;
  const NEAR_EDGE = 120;
  const RECENT_KEY = "av.recentEmoji";
  const LONG_TEXT = 2000;
  const LONG_LINES = 40;
  const PREVIEW_BYTES = 512 * 1024;

  const states = new Map();
  const pending = new Map();
  const drafts = new Map();
  const rowCache = new Map();
  const typing = new Map();
  let chat = null;
  let reply = null;
  let editing = null;
  let files = [];
  let mentionNames = new Map();
  let suggest = null;
  let panel = null;
  let typingSentAt = 0;
  let newBelow = 0;
  let atBottom = true;
  let busyOlder = false;
  let busyNewer = false;
  let menu = null;
  let pinsOpen = false;
  let pinsList = null;
  let pinIndex = 0;

  const $ = (id) => document.getElementById(id);
  const me = () => AV.state.me;
  const isDm = (c) => c.startsWith("dm:");
  const peerOf = (id) => AV.state.peers.find((p) => p.id === id);
  const nameOf = (id) => (id === me().id ? me().name : (peerOf(id) || { name: "Participante" }).name);
  const adminOn = () => !!(me().admin && me().admin.active);
  const fmt = { me: null, nameOf, openLink: (url) => AV.openLink(url) };

  function state(c) {
    if (!states.has(c)) states.set(c, { msgs: [], hasOlder: false, hasNewer: false, loaded: false, error: null, anchor: null, unreadFrom: null });
    return states.get(c);
  }

  const pendingOf = (c) => pending.get(c) || [];
  const lastSeq = (s) => (s.msgs.length ? s.msgs[s.msgs.length - 1].seq : 0);

  function merge(s, list) {
    const byId = new Map(s.msgs.map((m) => [m.id, m]));
    list.forEach((m) => byId.set(m.id, m));
    s.msgs = [...byId.values()].sort((a, b) => a.seq - b.seq);
  }

  async function page(c, params) {
    const q = new URLSearchParams({ chat: c, limit: String(PAGE), ...params });
    return AV.api("GET", "/api/messages?" + q);
  }

  /* ---- selecting and loading ---- */

  function select(c) {
    if (AV.voice.recording()) AV.voice.finish(false);
    if (chat) {
      drafts.set(chat, $("composer").value);
      state(chat).anchor = atBottom ? null : captureAnchor();
    }
    chat = c;
    reply = null;
    editing = null;
    suggest = null;
    panel = null;
    files = [];
    newBelow = 0;
    mentionNames = new Map();
    closeMenu();
    pinsList = null;
    pinIndex = 0;
    renderPins();
    loadPins();
    $("composer").value = drafts.get(c) || "";
    autosize();
    renderComposer();
    const s = state(c);
    if (s.loaded) {
      render();
      restoreAnchor(s.anchor);
      refreshTail(c);
    } else {
      render();
      load(c);
    }
  }

  async function load(c) {
    const s = state(c);
    s.error = null;
    try {
      const msgs = await page(c, {});
      s.msgs = msgs;
      s.hasOlder = msgs.length === PAGE;
      s.hasNewer = false;
      s.loaded = true;
      s.unreadFrom = firstUnread(c, msgs);
    } catch (e) {
      s.error = e.message;
    }
    if (c !== chat) return;
    render();
    const divider = s.unreadFrom && document.querySelector('[data-id="' + CSS.escape(s.unreadFrom) + '"]');
    if (divider) scrollToNode(divider, false); else scrollBottom();
    markRead();
  }

  /* Where the "novas" line goes: before the last unread messages from others, as many as the server counted. */
  function firstUnread(c, msgs) {
    const u = (AV.state.unread || {})[c];
    if (!u || !u.count) return null;
    let left = u.count;
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].mine) continue;
      if (--left === 0) return msgs[i].id;
    }
    return msgs.length ? msgs[0].id : null;
  }

  async function refreshTail(c) {
    const s = state(c);
    if (!s.loaded || s.hasNewer) return;
    try {
      const msgs = await page(c, { after: String(lastSeq(s)), limit: "100" });
      if (msgs.length === 100) { await load(c); return; }
      merge(s, msgs);
      if (c === chat && msgs.length) render();
    } catch (e) { /* The banner already says the server is unreachable; the page keeps what it has. */ }
  }

  async function loadOlder() {
    const c = chat;
    const s = state(c);
    if (busyOlder || !s.hasOlder || !s.msgs.length) return;
    busyOlder = true;
    try {
      const msgs = await page(c, { before: String(s.msgs[0].seq) });
      if (c !== chat) return;
      s.hasOlder = msgs.length === PAGE;
      const anchor = captureAnchor();
      merge(s, msgs);
      render();
      restoreAnchor(anchor);
    } catch (e) { AV.fail(e); } finally { busyOlder = false; }
  }

  async function loadNewer() {
    const c = chat;
    const s = state(c);
    if (busyNewer || !s.hasNewer) return;
    busyNewer = true;
    try {
      const msgs = await page(c, { after: String(lastSeq(s)) });
      if (c !== chat) return;
      s.hasNewer = msgs.length === PAGE;
      const anchor = captureAnchor();
      merge(s, msgs);
      render();
      restoreAnchor(anchor);
    } catch (e) { AV.fail(e); } finally { busyNewer = false; }
  }

  async function jumpTo(id, seq) {
    const s = state(chat);
    let node = document.querySelector('.msg[data-id="' + CSS.escape(id) + '"]');
    if (!node && seq) {
      try {
        const msgs = await page(chat, { around: String(seq) });
        s.msgs = msgs;
        s.hasOlder = true;
        s.hasNewer = true;
        render();
        node = document.querySelector('.msg[data-id="' + CSS.escape(id) + '"]');
      } catch (e) { AV.fail(e); return; }
    }
    if (!node) { AV.toast("Essa mensagem não está mais disponível."); return; }
    scrollToNode(node, true);
    node.classList.remove("msg-flash");
    void node.offsetWidth;
    node.classList.add("msg-flash");
  }

  function jumpToPresent() {
    const s = state(chat);
    if (s.hasNewer) { s.loaded = false; load(chat); } else scrollBottom();
    newBelow = 0;
    renderJump();
  }

  /* ---- scrolling ---- */

  const box = () => $("messages");

  function scrollBottom() {
    const b = box();
    b.scrollTop = b.scrollHeight;
    atBottom = true;
  }

  function scrollToNode(node, center) {
    const b = box();
    b.scrollTop = node.offsetTop - (center ? (b.clientHeight - node.offsetHeight) / 2 : 24);
    onScroll();
  }

  function captureAnchor() {
    const b = box();
    for (const node of b.querySelectorAll(".msg[data-id]")) {
      if (node.offsetTop + node.offsetHeight > b.scrollTop) return { id: node.dataset.id, offset: node.offsetTop - b.scrollTop };
    }
    return null;
  }

  function restoreAnchor(anchor) {
    if (!anchor) { scrollBottom(); return; }
    const node = box().querySelector('.msg[data-id="' + CSS.escape(anchor.id) + '"]');
    if (node) box().scrollTop = node.offsetTop - anchor.offset; else scrollBottom();
    onScroll();
  }

  function onScroll() {
    const b = box();
    const s = state(chat);
    atBottom = b.scrollHeight - b.scrollTop - b.clientHeight < NEAR_EDGE;
    if (b.scrollTop < 400 && s.hasOlder) loadOlder();
    if (atBottom && s.hasNewer) loadNewer();
    if (atBottom && !s.hasNewer) { newBelow = 0; markRead(); }
    renderJump();
    closeMenu();
  }

  /* ---- reading ---- */

  function markRead() {
    if (!chat || !document.hasFocus() || !atBottom) return;
    const s = state(chat);
    if (s.hasNewer || !s.msgs.length) return;
    const unread = (AV.state.unread || {})[chat];
    const seq = lastSeq(s);
    if (!unread && s.readSeq >= seq) return;
    s.readSeq = seq;
    if (AV.state.unread) delete AV.state.unread[chat];
    AV.renderNav();
    AV.api("POST", "/api/messages/read", { chat, seq }).catch(() => {});
  }

  /* ---- rendering the list ---- */

  function full(ts) {
    return new Date(ts).toLocaleString("pt-BR", { dateStyle: "full", timeStyle: "short" });
  }

  function intro() {
    const title = isDm(chat) ? nameOf(chat.slice(3)) : (AV.state.groups.find((g) => g.id === chat) || { name: "conversa" }).name;
    return E("div", { class: "chat-intro" },
      isDm(chat) ? AV.avatar(chat.slice(3), title, "avatar-lg") : E("div", { class: "chat-intro-icon" }, AV.icon(chat === "publico" ? "network" : "users", "!h-7 !w-7")),
      E("div", { class: "text-xl font-bold" }, isDm(chat) ? title : "Bem-vindo a " + title),
      E("div", { class: "text-muted" }, isDm(chat) ? "Este é o começo da sua conversa com " + title + "." : "Este é o começo da conversa em " + title + "."));
  }

  function render() {
    if (!chat) return;
    fmt.me = me().id;
    const s = state(chat);
    const nodes = [];
    if (s.error) {
      nodes.push(E("div", { class: "empty" }, AV.icon("alert", "!h-8 !w-8"), E("div", { class: "font-semibold text-ink" }, "Não foi possível carregar as mensagens."),
        E("div", {}, s.error), E("button", { class: "btn", onclick: () => load(chat) }, "Tentar de novo")));
    } else if (!s.loaded) {
      nodes.push(E("div", { class: "empty" }, E("div", { class: "spinner", "aria-hidden": "true" }), "Carregando mensagens…"));
    } else {
      nodes.push(s.hasOlder ? E("div", { class: "chat-more" }, E("div", { class: "spinner", "aria-hidden": "true" }), "Carregando mensagens anteriores…") : intro());
      let prev = null;
      for (const m of s.msgs) {
        if (!prev || new Date(prev.ts).toDateString() !== new Date(m.ts).toDateString()) nodes.push(E("div", { class: "day-sep", role: "separator" }, AV.day(m.ts)));
        if (s.unreadFrom === m.id) nodes.push(E("div", { class: "unread-sep", role: "separator" }, E("span", {}, "Novas")));
        nodes.push(row(m, prev));
        prev = m;
      }
      if (s.hasNewer) nodes.push(E("div", { class: "chat-more" }, E("div", { class: "spinner", "aria-hidden": "true" }), "Carregando mensagens mais novas…"));
      else pendingOf(chat).forEach((p) => { nodes.push(pendingRow(p, prev)); prev = { from: me().id, ts: p.ts }; });
    }
    const keep = atBottom;
    AV.fill($("msg-list"), ...nodes);
    if (keep && !s.hasNewer && s.loaded) scrollBottom();
    renderJump();
    renderTyping();
  }

  /* A row is rebuilt only when something it shows changed, so images and videos are not loaded again on every event. */
  function row(m, prev) {
    const compact = !!(prev && prev.from === m.from && !m.replyTo && m.ts - prev.ts < GROUP_MS
      && new Date(prev.ts).toDateString() === new Date(m.ts).toDateString());
    const key = [compact, m.edited, m.text, JSON.stringify(m.reactions || []), JSON.stringify(m.embeds || []), JSON.stringify(m.attachments || []),
      JSON.stringify(m.ref || null), m.pinned, editing === m.id, adminOn(), AV.avatarVersion(m.from), nameOf(m.from)].join("|");
    const cached = rowCache.get(m.id);
    if (cached && cached.key === key) return cached.node;
    const node = buildRow(m, compact);
    rowCache.set(m.id, { key, node });
    return node;
  }

  function buildRow(m, compact) {
    const mentioned = (m.mentions || []).includes(me().id) || (m.ref && m.ref.from === me().id && !m.mine);
    const node = E("div", { class: "msg" + (compact ? " msg-compact" : "") + (mentioned ? " msg-mention" : ""), dataset: { id: m.id }, role: "article",
      "aria-label": nameOf(m.from) + ", " + full(m.ts), oncontextmenu: (e) => { e.preventDefault(); openMenu(m, e.clientX, e.clientY); } },
    m.replyTo && refBar(m),
    E("div", { class: "msg-row" },
      compact ? E("span", { class: "msg-gutter-time", title: full(m.ts) }, AV.time(m.ts))
        : E("button", { class: "msg-avatar", "aria-label": "Mencionar " + nameOf(m.from), onclick: () => insertMention(m.from) }, AV.avatar(m.from, nameOf(m.from))),
      E("div", { class: "msg-body" },
        !compact && E("div", { class: "msg-head" },
          E("span", { class: "msg-name" }, m.name && m.from !== me().id && !peerOf(m.from) ? m.name : nameOf(m.from)),
          E("time", { class: "msg-time", datetime: new Date(m.ts).toISOString(), title: full(m.ts) }, AV.day(m.ts) === "Hoje" ? "Hoje às " + AV.time(m.ts) : AV.day(m.ts) + " às " + AV.time(m.ts)),
          m.pinned > 0 && E("span", { class: "msg-pin", title: "Fixada por " + nameOf(m.pinnedBy || m.from) }, AV.icon("pin", "ic-sm"), "Fixada")),
        compact && m.pinned > 0 && E("span", { class: "msg-pin", title: "Fixada por " + nameOf(m.pinnedBy || m.from) }, AV.icon("pin", "ic-sm"), "Fixada"),
        editing === m.id ? editBox(m) : m.text && E("div", { class: "msg-text selectable" }, AV.md.render(m.text, fmt),
          m.edited ? E("span", { class: "msg-edited", title: "Editada em " + full(m.edited) }, " (editada)") : null),
        attachmentsOf(m), embedsOf(m), reactionsOf(m))),
    toolbar(m));
    return node;
  }

  function refBar(m) {
    const r = m.ref;
    if (!r || r.missing) {
      return E("div", { class: "msg-ref msg-ref-missing" }, E("span", { class: "msg-ref-line" }), AV.icon("x", "ic-sm"), E("em", {}, "A mensagem original foi apagada."));
    }
    const text = AV.md.plain(r.text || "", nameOf) || (r.attachments ? "Clique para ver o anexo" : "");
    return E("button", { class: "msg-ref", title: "Ir para a mensagem respondida", onclick: () => jumpTo(r.id, r.seq) },
      E("span", { class: "msg-ref-line" }), AV.avatar(r.from, nameOf(r.from), "avatar-xs"), E("b", {}, "@" + nameOf(r.from)),
      E("span", { class: "truncate" }, text));
  }

  function toolbar(m) {
    const mine = m.from === me().id;
    const b = (icon, label, run) => E("button", { class: "btn btn-ghost btn-icon", title: label, "aria-label": label, onclick: (e) => { e.stopPropagation(); run(e); } }, AV.icon(icon, "ic-sm"));
    return E("div", { class: "msg-toolbar card" },
      b("smile", "Adicionar reação", () => openPicker(m)),
      b("reply", "Responder", () => startReply(m)),
      mine && b("edit", "Editar", () => startEdit(m)),
      b("more", "Mais opções", (e) => { const r = e.currentTarget.getBoundingClientRect(); openMenu(m, r.left, r.bottom + 4); }));
  }

  function attachmentsOf(m) {
    const list = m.attachments || [];
    if (!list.length) return null;
    const images = list.filter((a) => a.type.startsWith("image/") && !a.expired);
    return E("div", { class: "msg-files" }, list.map((a) => {
      if (a.expired) return E("div", { class: "att-file att-expired" }, AV.icon("alert", "ic-sm"), E("span", {}, a.name + " — anexo expirado"));
      const src = "/api/files/" + encodeURIComponent(a.id);
      if (a.type.startsWith("image/")) {
        const size = fit(a.width, a.height, 400, 320);
        return E("button", { class: "att-media", title: a.name, "aria-label": "Abrir imagem " + a.name, onclick: () => lightbox(images, images.indexOf(a)) },
          E("img", { src, alt: a.name, width: size.w, height: size.h, loading: "lazy", draggable: "false" }));
      }
      if (a.type.startsWith("video/")) {
        const size = fit(a.width || 640, a.height || 360, 400, 320);
        return E("div", { class: "att-media" }, E("video", { src, controls: true, preload: "metadata", width: size.w, height: size.h }), fileCard(a, true));
      }
      if (/\.(md|markdown|txt)$/i.test(a.name) || /^text\/(markdown|plain)/.test(a.type)) return textCard(a);
      if (a.type.startsWith("audio/")) {
        const voice = a.name.startsWith("Mensagem de voz");
        return E("div", { class: "att-audio" }, AV.voice.player(a, { id: m.from, name: nameOf(m.from) }), !voice && fileCard(a, true));
      }
      return fileCard(a, false);
    }));
  }

  function fileCard(a, slim) {
    return E("div", { class: "att-file" + (slim ? " att-file-slim" : "") }, AV.icon("file", "ic-sm"),
      E("div", { class: "min-w-0 flex-1" }, E("div", { class: "truncate font-semibold" }, a.name), E("small", { class: "text-muted" }, AV.bytes(a.size))),
      E("button", { class: "btn btn-ghost btn-icon", title: "Salvar em Downloads", "aria-label": "Salvar " + a.name + " em Downloads", onclick: () => saveFile(a) }, AV.icon("download", "ic-sm")));
  }

  /* A text file shows inside the conversation: Markdown formatted, plain text as it is, long ones folded. */
  function textCard(a) {
    const md = /\.(md|markdown)$/i.test(a.name) || a.type.startsWith("text/markdown");
    const body = E("div", { class: "md-file-body msg-text selectable" }, E("div", { class: "chat-more" }, E("div", { class: "spinner", "aria-hidden": "true" }), "Abrindo o texto…"));
    const more = E("button", { class: "linkish md-file-more", hidden: true }, "Mostrar tudo");
    let text = null;
    const copy = E("button", { class: "btn btn-ghost btn-sm", title: "Copiar o texto", "aria-label": "Copiar o texto", disabled: true, onclick: () => navigator.clipboard.writeText(text).then(() => AV.toast("Texto copiado.", "ok")) }, AV.icon("copy", "ic-sm"), E("span", { class: "hidden sm:inline" }, "Copiar"));
    const card = E("div", { class: "md-file card" },
      E("div", { class: "md-file-head" }, AV.icon("file", "ic-sm"), E("div", { class: "min-w-0 flex-1" }, E("div", { class: "truncate font-semibold" }, a.name), E("small", { class: "text-muted" }, AV.bytes(a.size) + (md ? " · Markdown" : " · Texto"))),
        copy, E("button", { class: "btn btn-ghost btn-sm", title: "Salvar em Downloads", "aria-label": "Baixar " + a.name, onclick: () => saveFile(a) }, AV.icon("download", "ic-sm"), E("span", { class: "hidden sm:inline" }, "Baixar"))),
      body, more);
    more.addEventListener("click", () => { const open = card.classList.toggle("open"); more.textContent = open ? "Mostrar menos" : "Mostrar tudo"; });
    if (a.size > PREVIEW_BYTES) {
      AV.fill(body, E("div", { class: "text-sm text-muted" }, "Arquivo grande demais para mostrar aqui. Baixe para ler."));
      return card;
    }
    fetch("/api/files/" + encodeURIComponent(a.id)).then((r) => { if (!r.ok) throw new Error(); return r.text(); }).then((t) => {
      text = t;
      copy.disabled = false;
      AV.fill(body, md ? AV.md.render(t, fmt) : t);
      requestAnimationFrame(() => { more.hidden = body.scrollHeight <= body.clientHeight + 4; });
    }).catch(() => AV.fill(body, E("div", { class: "text-sm text-muted" }, "Não foi possível abrir o texto.")));
    return card;
  }

  async function saveFile(a) {
    try {
      await AV.api("POST", "/api/files/" + encodeURIComponent(a.id) + "/save", { name: a.name });
      AV.toast(a.name + " foi salvo em Downloads.", "ok");
    } catch (e) { AV.fail(e); }
  }

  function fit(w, h, maxW, maxH) {
    if (!w || !h) return { w: maxW, h: Math.round(maxW * 0.6) };
    const k = Math.min(1, maxW / w, maxH / h);
    return { w: Math.max(1, Math.round(w * k)), h: Math.max(1, Math.round(h * k)) };
  }

  function embedsOf(m) {
    const list = m.embeds || [];
    if (!list.length) return null;
    return list.map((em, i) => {
      const size = em.image ? fit(em.imageWidth || 400, em.imageHeight || 210, 400, 300) : null;
      const node = E("div", { class: "embed" },
        em.site && E("div", { class: "embed-site" }, em.site),
        em.title && E("a", { class: "embed-title md-link", href: em.url, onclick: (e) => { e.preventDefault(); AV.openLink(em.url); } }, em.title),
        em.description && E("div", { class: "embed-desc" }, em.description),
        em.image && E("img", { class: "embed-img", src: "/api/embeds/" + encodeURIComponent(m.id) + "/" + i, alt: em.title || "", width: size.w, height: size.h, loading: "lazy", draggable: "false",
          onerror: (e) => e.target.remove() }));
      if (/^#[0-9a-fA-F]{6}$/.test(em.color || "")) node.style.borderLeftColor = em.color;
      return node;
    });
  }

  function reactionsOf(m) {
    const list = m.reactions || [];
    if (!list.length) return null;
    return E("div", { class: "reactions" }, list.map((r) => {
      const mine = (r.users || []).includes(me().id);
      const who = (r.users || []).slice(0, 12).map(nameOf).join(", ") + (r.count > 12 ? " e mais " + (r.count - 12) : "");
      return E("button", { class: "react" + (mine ? " on" : ""), "aria-pressed": String(mine), title: who + " reagiu com " + r.emoji,
        onclick: () => react(m, r.emoji, !mine) }, E("span", { class: "react-emoji" }, r.emoji), E("span", {}, r.count));
    }), E("button", { class: "react react-add", title: "Adicionar reação", "aria-label": "Adicionar reação", onclick: () => openPicker(m) }, AV.icon("smile", "ic-sm")));
  }

  function pendingRow(p, prev) {
    const compact = !!(prev && prev.from === me().id && !p.replyTo && p.ts - prev.ts < GROUP_MS);
    const failed = p.status === "failed";
    return E("div", { class: "msg" + (compact ? " msg-compact" : "") + (failed ? " msg-failed" : " msg-pending"), dataset: { nonce: p.nonce } },
      p.replyTo && p.ref && E("div", { class: "msg-ref" }, E("span", { class: "msg-ref-line" }), E("b", {}, "@" + nameOf(p.ref.from)), E("span", { class: "truncate" }, AV.md.plain(p.ref.text || "", nameOf))),
      E("div", { class: "msg-row" },
        compact ? E("span", { class: "msg-gutter-time" }, AV.time(p.ts)) : E("div", { class: "msg-avatar" }, AV.avatar(me().id, me().name)),
        E("div", { class: "msg-body" },
          !compact && E("div", { class: "msg-head" }, E("span", { class: "msg-name" }, me().name), E("span", { class: "msg-time" }, failed ? "Não enviada" : "Enviando…")),
          p.text && E("div", { class: "msg-text" }, AV.md.render(p.text, fmt)),
          p.voice ? E("div", { class: "text-sm text-muted" }, AV.icon("mic", "ic-sm"), " Mensagem de voz")
            : p.upload ? E("div", { class: "text-sm text-muted" }, AV.icon("file", "ic-sm"), " Texto longo, enviado como " + p.upload.name)
            : p.files.length > 0 && E("div", { class: "text-sm text-muted" }, p.files.length === 1 ? "1 anexo" : p.files.length + " anexos"),
          failed && E("div", { class: "msg-fail" }, AV.icon("alert", "ic-sm"), E("span", {}, p.error || "Não foi possível enviar."),
            E("button", { class: "btn btn-sm", onclick: () => deliver(p) }, "Tentar de novo"),
            E("button", { class: "btn btn-ghost btn-sm", onclick: () => { pending.set(chat, pendingOf(chat).filter((x) => x !== p)); render(); } }, "Descartar")))));
  }

  function renderJump() {
    const s = chat ? state(chat) : null;
    const bar = $("chat-jump");
    if (s && s.hasNewer) {
      AV.fill(bar, E("span", { class: "flex-1" }, "Você está vendo mensagens antigas."), E("button", { class: "btn btn-sm", onclick: jumpToPresent }, "Ir para o presente"));
      bar.hidden = false;
    } else if (newBelow > 0 && !atBottom) {
      AV.fill(bar, E("span", { class: "flex-1" }, newBelow === 1 ? "1 nova mensagem" : newBelow + " novas mensagens"), E("button", { class: "btn btn-sm btn-primary", onclick: jumpToPresent }, AV.icon("arrow-down", "ic-sm"), "Ver agora"));
      bar.hidden = false;
    } else if (!atBottom && farFromEnd()) {
      AV.fill(bar, E("span", { class: "min-w-0 flex-1 truncate" }, "Você está vendo mensagens anteriores."), E("button", { class: "btn btn-sm", onclick: jumpToPresent }, AV.icon("arrow-down", "ic-sm"), "Ir para a mais recente"));
      bar.hidden = false;
    } else {
      bar.hidden = true;
    }
  }

  /* More than half a screen above the end: worth offering the way back. */
  function farFromEnd() {
    const b = box();
    return b.scrollHeight - b.scrollTop - b.clientHeight > Math.max(300, b.clientHeight * 0.5);
  }

  function renderTyping() {
    const line = $("typing-line");
    const now = Date.now();
    const who = [...((typing.get(chat) || new Map()).entries())].filter(([, t]) => t.until > now).map(([, t]) => t.name);
    if (!who.length) { line.textContent = ""; return; }
    const text = who.length === 1 ? who[0] + " está digitando…" : who.length <= 3 ? who.slice(0, -1).join(", ") + " e " + who[who.length - 1] + " estão digitando…" : "Várias pessoas estão digitando…";
    AV.fill(line, E("span", { class: "typing-dots", "aria-hidden": "true" }, E("i"), E("i"), E("i")), text);
  }

  /* ---- writing ---- */

  function autosize() {
    const c = $("composer");
    c.style.height = "auto";
    c.style.height = Math.min(c.scrollHeight, 220) + "px";
  }

  function renderComposer() {
    const muted = me().mutedUntil > Date.now();
    const c = $("composer");
    c.disabled = muted;
    $("btn-send").disabled = muted;
    $("btn-attach").disabled = muted;
    $("btn-mic").disabled = muted;
    syncSendButton();
    if (muted) c.placeholder = "Você está silenciado até " + new Date(me().mutedUntil).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" }) + ".";
    AV.fill($("composer-reply"), reply && [
      E("span", { class: "flex-1 truncate" }, "Respondendo a ", E("b", {}, nameOf(reply.from))),
      E("button", { class: "btn btn-ghost btn-icon !h-7 !w-7", title: "Cancelar resposta", "aria-label": "Cancelar resposta", onclick: cancelReply }, AV.icon("x", "ic-sm"))]);
    $("composer-reply").hidden = !reply;
    AV.fill($("composer-files"), files.map((f) => E("div", { class: "file-chip" + (f.status === "error" ? " error" : "") },
      f.preview ? E("img", { src: f.preview, alt: "", draggable: "false" }) : AV.icon("file", "ic-sm"),
      E("div", { class: "min-w-0 flex-1" }, E("div", { class: "truncate text-sm font-semibold" }, f.name),
        E("small", { class: f.status === "error" ? "text-danger" : "text-muted" }, f.status === "error" ? f.error : f.status === "done" ? AV.bytes(f.size) : f.status === "preparing" ? "Preparando…" : "Enviando " + Math.round(f.progress * 100) + "%"),
        f.status === "uploading" && E("div", { class: "progress mt-1" }, E("i", { style: "width:" + Math.round(f.progress * 100) + "%" }))),
      E("button", { class: "btn btn-ghost btn-icon !h-7 !w-7", title: "Remover anexo", "aria-label": "Remover " + f.name, onclick: () => { files = files.filter((x) => x !== f); renderComposer(); } }, AV.icon("x", "ic-sm")))));
    $("composer-files").hidden = files.length === 0;
    renderSuggest();
    renderPanel();
  }

  /* As in phone messengers: an empty composer offers the microphone, anything typed or attached offers send. */
  function syncSendButton() {
    $("composer-box").classList.toggle("has-content", !!($("composer").value.trim() || files.length));
  }

  function startReply(m) {
    reply = { id: m.id, from: m.from, text: m.text, seq: m.seq };
    editing = null;
    renderComposer();
    $("composer").focus();
  }

  function cancelReply() {
    reply = null;
    renderComposer();
  }

  /* People are typed as @Name and travel as <@id>: the names chosen in this draft are remembered to convert them back. */
  function encodeMentions(text, names) {
    let out = text;
    [...names.entries()].sort((a, b) => b[0].length - a[0].length).forEach(([name, id]) => {
      out = out.split("@" + name).join("<@" + id + ">");
    });
    return AV.emoji.shortcodes(out);
  }

  function decodeMentions(text, names) {
    return text.replace(/<@([0-9a-f]{20})>/g, (_, id) => { const n = nameOf(id); names.set(n, id); return "@" + n; });
  }

  function insertMention(id) {
    const c = $("composer");
    const name = nameOf(id);
    mentionNames.set(name, id);
    c.setRangeText("@" + name + " ", c.selectionStart, c.selectionEnd, "end");
    c.focus();
    autosize();
  }

  async function send() {
    const c = $("composer");
    if (suggest) { pickSuggest(); return; }
    if (files.some((f) => f.status === "uploading" || f.status === "preparing")) { AV.toast("Espere os anexos terminarem de enviar."); return; }
    if (files.some((f) => f.status === "error")) { AV.toast("Remova os anexos que falharam antes de enviar."); return; }
    const typed = c.value.trim();
    /* A long text travels as a formatted .md file, which the conversation shows with copy and download, as people expect. */
    const long = typed.length > LONG_TEXT || typed.split("\n").length > LONG_LINES;
    const text = long ? "" : encodeMentions(typed, mentionNames);
    const ready = files.filter((f) => f.status === "done");
    if (!typed && !ready.length) return;
    const fileName = long ? await askFileName(typed) : null;
    if (long && !fileName) return;
    const p = { nonce: crypto.randomUUID(), chat, text, replyTo: reply ? reply.id : null, ref: reply, files: ready.map((f) => f.att.id), ts: Date.now(), status: "sending",
      upload: long ? new File([AV.emoji.shortcodes(typed)], fileName, { type: "text/markdown" }) : null };
    pending.set(chat, [...pendingOf(chat), p]);
    c.value = "";
    drafts.delete(chat);
    reply = null;
    files = [];
    mentionNames = new Map();
    typingSentAt = 0;
    autosize();
    renderComposer();
    const s = state(chat);
    if (s.hasNewer) { s.loaded = false; await load(chat); }
    atBottom = true;
    render();
    scrollBottom();
    deliver(p);
  }

  /* A retry reuses the nonce, so the server can never store the same message twice. */
  async function deliver(p) {
    p.status = "sending";
    if (p.chat === chat) render();
    try {
      if (p.upload && !p.uploaded) { p.files = [...p.files, (await upload(p.upload, () => {})).id]; p.uploaded = true; }
      const m = await AV.api("POST", "/api/messages", { chat: p.chat, text: p.text, nonce: p.nonce, replyTo: p.replyTo, attachments: p.files });
      accept(m);
    } catch (e) {
      p.status = "failed";
      p.error = e.message;
      AV.sound("error");
      if (p.chat === chat) render();
    }
  }

  function accept(m) {
    const list = pendingOf(m.chat);
    if (m.nonce && list.some((p) => p.nonce === m.nonce)) pending.set(m.chat, list.filter((p) => p.nonce !== m.nonce));
    const s = states.get(m.chat);
    if (s && s.loaded && !s.hasNewer) merge(s, [m]);
    if (m.chat === chat) render();
  }

  /* The name of a long text's file: suggested from its title or first line, always ending in .md, never with folder characters. */
  function suggestName(text) {
    const first = (text.split("\n").find((l) => l.trim()) || "").replace(/^#+\s*/, "").replace(/[*_~`|>]/g, "");
    return cleanName(first.slice(0, 50)) || "mensagem";
  }

  function cleanName(name) {
    return name.replace(/\.md$/i, "").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "").replace(/\s+/g, " ").trim().replace(/[. ]+$/, "").slice(0, 80);
  }

  function askFileName(text) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (value) => { if (!done) { done = true; resolve(value); } };
      const input = E("input", { class: "input", maxlength: 80, "aria-label": "Nome do arquivo", value: suggestName(text) });
      const confirm = (close) => {
        const name = cleanName(input.value);
        if (!name) { AV.toast("Dê um nome ao arquivo.", "error"); return; }
        finish(name + ".md");
        close();
      };
      const close = AV.modal("Nome do arquivo", (body, closeIt) => {
        body.append(E("p", { class: "mb-3 text-sm text-muted" }, "O texto é longo e vai como um arquivo Markdown, mostrado formatado na conversa."),
          E("div", { class: "flex items-center gap-2" }, input, E("span", { class: "text-muted" }, ".md")));
        input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); confirm(closeIt); } });
      }, [
        { label: "Cancelar", run: (c) => c() },
        { label: "Enviar", primary: true, run: (c) => confirm(c) },
      ], () => finish(null));
      input.select();
      void close;
    });
  }

  /* A finished recording is sent at once, as its own message, keeping the reply it was recorded for. */
  function sendVoice(file) {
    const target = chat;
    const p = { nonce: crypto.randomUUID(), chat: target, text: "", replyTo: reply ? reply.id : null, ref: reply, files: [], upload: file, voice: true, ts: Date.now(), status: "sending" };
    reply = null;
    renderComposer();
    pending.set(target, [...pendingOf(target), p]);
    atBottom = true;
    render();
    scrollBottom();
    deliver(p);
  }

  function startEdit(m) {
    if (m.from !== me().id) return;
    editing = m.id;
    render();
    const area = document.querySelector('.msg[data-id="' + CSS.escape(m.id) + '"] textarea');
    if (area) { area.focus(); area.setSelectionRange(area.value.length, area.value.length); }
  }

  function editBox(m) {
    const names = new Map();
    const area = E("textarea", { class: "input msg-edit", rows: 1, maxlength: 4000, "aria-label": "Editar mensagem" });
    area.value = decodeMentions(m.text || "", names);
    const size = () => { area.style.height = "auto"; area.style.height = Math.min(area.scrollHeight, 300) + "px"; };
    const cancel = () => { editing = null; render(); $("composer").focus(); };
    const save = async () => {
      const text = encodeMentions(area.value.trim(), names);
      if (text === m.text) { cancel(); return; }
      if (!text && !(m.attachments || []).length) { editing = null; remove(m, false); return; }
      try {
        accept(await AV.api("PATCH", "/api/messages/" + encodeURIComponent(m.id), { text }));
        editing = null;
        render();
        $("composer").focus();
      } catch (e) { AV.fail(e); }
    };
    area.addEventListener("input", size);
    area.addEventListener("keydown", (e) => {
      if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); cancel(); }
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); save(); }
    });
    requestAnimationFrame(size);
    return E("div", { class: "msg-edit-box" }, area,
      E("small", { class: "text-muted" }, "Esc para ", E("button", { class: "linkish", onclick: cancel }, "cancelar"), " · Enter para ", E("button", { class: "linkish", onclick: save }, "salvar")));
  }

  async function remove(m, skipConfirm) {
    if (!skipConfirm && !(await AV.confirm("Apagar mensagem?", "Ela some para todos e não pode ser recuperada.", "Apagar", true))) return;
    try {
      await AV.api("DELETE", "/api/messages/" + encodeURIComponent(m.id));
      dropMessage(m.chat, m.id);
    } catch (e) { AV.fail(e); }
  }

  async function react(m, emoji, on) {
    try {
      accept(await AV.api("PUT", "/api/messages/" + encodeURIComponent(m.id) + "/reactions", { emoji, on }));
      if (on) rememberEmoji(emoji);
    } catch (e) { AV.fail(e); }
  }

  function sendTyping() {
    const now = Date.now();
    if (!$("composer").value.trim() || now - typingSentAt < TYPING_SEND_MS) return;
    typingSentAt = now;
    AV.api("POST", "/api/messages/typing", { chat }).catch(() => {});
  }

  /* ---- autocomplete: @people and :emoji ---- */

  function participants() {
    if (isDm(chat)) return [chat.slice(3)].filter(peerOf);
    const peers = [...AV.state.peers].sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
    const inGroup = chat === "publico" ? peers : peers.filter((p) => p.groups.includes(chat) || !p.online);
    return inGroup.map((p) => p.id);
  }

  function updateSuggest() {
    const c = $("composer");
    const before = c.value.slice(0, c.selectionStart);
    let m;
    if ((m = /(^|\s)@([^\s@]{0,32})$/.exec(before))) {
      const q = AV.emoji.fold(m[2]);
      const items = participants().filter((id) => AV.emoji.fold(nameOf(id)).includes(q)).slice(0, 8)
        .map((id) => ({ label: nameOf(id), id, avatar: true }));
      suggest = items.length ? { kind: "mention", items, index: 0, start: before.length - m[2].length - 1 } : null;
    } else if ((m = /(^|\s):([a-z0-9_]{2,30})$/i.exec(before))) {
      const items = AV.emoji.search(m[2], 8).map((e) => ({ label: ":" + e.code + ":", char: e.char }));
      suggest = items.length ? { kind: "emoji", items, index: 0, start: before.length - m[2].length - 1 } : null;
    } else {
      suggest = null;
    }
    renderSuggest();
  }

  function renderSuggest() {
    const host = $("composer-suggest");
    host.hidden = !suggest;
    if (!suggest) { host.replaceChildren(); return; }
    AV.fill(host, E("div", { class: "suggest-title" }, suggest.kind === "mention" ? "Pessoas" : "Emojis"),
      suggest.items.map((it, i) => E("button", { class: "suggest-item", role: "option", "aria-selected": String(i === suggest.index),
        onmousedown: (e) => { e.preventDefault(); suggest.index = i; pickSuggest(); } },
      it.avatar ? AV.avatar(it.id, it.label, "avatar-xs") : E("span", { class: "text-lg" }, it.char), E("span", { class: "truncate" }, it.label))));
  }

  function pickSuggest() {
    const c = $("composer");
    const it = suggest.items[suggest.index];
    const insert = suggest.kind === "mention" ? "@" + it.label + " " : it.char + " ";
    if (suggest.kind === "mention") mentionNames.set(it.label, it.id);
    c.setRangeText(insert, suggest.start, c.selectionStart, "end");
    suggest = null;
    renderSuggest();
    autosize();
    c.focus();
  }

  /* ---- emoji panel (in the composer area, not floating) ---- */

  function recentEmoji() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) || "[]").slice(0, 16); } catch (e) { return []; }
  }

  function rememberEmoji(char) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify([char, ...recentEmoji().filter((x) => x !== char)].slice(0, 16))); } catch (e) { /* only a convenience */ }
  }

  function openPicker(m) {
    panel = { target: m || null, query: "" };
    renderPanel();
    const input = $("emoji-panel").querySelector("input");
    if (input) input.focus();
  }

  function closePicker() {
    panel = null;
    renderPanel();
  }

  function renderPanel() {
    const host = $("emoji-panel");
    host.hidden = !panel;
    if (!panel) { host.replaceChildren(); return; }
    const choose = (char) => {
      if (panel.target) { const target = panel.target; closePicker(); react(target, char, true); return; }
      rememberEmoji(char);
      const c = $("composer");
      c.setRangeText(char, c.selectionStart, c.selectionEnd, "end");
      autosize();
      c.focus();
    };
    const grid = E("div", { class: "emoji-grid", role: "listbox", "aria-label": "Emojis" });
    const fillGrid = () => AV.fill(grid, AV.emoji.search(panel.query, 400).map((e) => E("button", { class: "emoji-btn", title: ":" + e.code + ":", "aria-label": e.code.replace(/_/g, " "), onclick: () => choose(e.char) }, e.char)));
    const search = E("input", { class: "input", placeholder: "Buscar emoji", "aria-label": "Buscar emoji", value: panel.query,
      oninput: (e) => { panel.query = e.target.value; fillGrid(); },
      onkeydown: (e) => { if (e.key === "Escape") { e.stopPropagation(); closePicker(); $("composer").focus(); } if (e.key === "Enter") { const first = grid.querySelector("button"); if (first) first.click(); } } });
    fillGrid();
    const recent = recentEmoji();
    AV.fill(host, E("div", { class: "flex items-center gap-2" },
      E("div", { class: "min-w-0 flex-1 truncate text-sm font-semibold" }, panel.target ? "Reagir à mensagem de " + nameOf(panel.target.from) : "Emojis"),
      E("button", { class: "btn btn-ghost btn-icon !h-7 !w-7", title: "Fechar", "aria-label": "Fechar emojis", onclick: closePicker }, AV.icon("x", "ic-sm"))),
    search, recent.length > 0 && !panel.query && E("div", { class: "emoji-recent" }, E("small", { class: "text-faint" }, "Recentes"),
      E("div", { class: "flex flex-wrap gap-1" }, recent.map((char) => E("button", { class: "emoji-btn", onclick: () => choose(char) }, char)))), grid);
  }

  /* ---- attachments ---- */

  function addFiles(list) {
    for (const file of list) {
      if (files.length >= MAX_FILES) { AV.toast("No máximo " + MAX_FILES + " anexos por mensagem."); break; }
      if (file.size > MAX_FILE_BYTES) { AV.toast(file.name + " passa de 25 MB.", "error"); continue; }
      const entry = { name: file.name, size: file.size, status: "preparing", progress: 0, preview: null };
      files.push(entry);
      prepare(entry, file);
    }
    renderComposer();
  }

  /* Photos are stored as WebP, at most 2560 px on the longest side, as decided for the project; GIFs keep their motion. */
  async function asStoredImage(file) {
    if (!/^image\/(png|jpeg|webp|bmp)$/.test(file.type)) return file;
    const bitmap = await createImageBitmap(file);
    const k = Math.min(1, MAX_IMAGE_SIDE / Math.max(bitmap.width, bitmap.height));
    if (file.type === "image/webp" && k === 1) { bitmap.close(); return file; }
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(bitmap.width * k);
    canvas.height = Math.round(bitmap.height * k);
    canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/webp", 0.85));
    if (!blob) throw new Error("Não foi possível converter a imagem.");
    return new File([blob], file.name.replace(/\.[^.]+$/, "") + ".webp", { type: "image/webp" });
  }

  async function prepare(entry, file) {
    try {
      const ready = await asStoredImage(file);
      entry.name = ready.name;
      entry.size = ready.size;
      if (ready.type.startsWith("image/")) entry.preview = URL.createObjectURL(ready);
      if (ready.size > MAX_FILE_BYTES) throw new Error("Passa de 25 MB.");
      entry.status = "uploading";
      renderComposer();
      entry.att = await upload(ready, (p) => { entry.progress = p; renderComposer(); });
      entry.status = "done";
    } catch (e) {
      entry.status = "error";
      entry.error = e.message || "Falhou.";
    }
    if (files.includes(entry)) renderComposer();
  }

  function upload(file, progress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/attachments");
      xhr.setRequestHeader("X-File-Name", encodeURIComponent(file.name));
      // Sent as binary with the real type beside it: a text body would be read by the input filter before the route.
      xhr.setRequestHeader("Content-Type", "application/octet-stream");
      xhr.setRequestHeader("X-File-Type", file.type || "application/octet-stream");
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) progress(e.loaded / e.total); };
      xhr.onload = () => {
        let data = null;
        try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
        if (xhr.status >= 200 && xhr.status < 300 && data && data.id) resolve(data);
        else reject(new Error((data && (data.title || data.message || data.error)) || "Não foi possível enviar o arquivo."));
      };
      xhr.onerror = () => reject(new Error("Sem conexão com o aplicativo."));
      xhr.send(file);
    });
  }

  /* ---- context menu: the one floating element, kept inside the window ---- */

  function closeMenu() {
    if (menu) { menu.remove(); menu = null; }
  }

  function openMenu(m, x, y) {
    closeMenu();
    const mine = m.from === me().id;
    const item = (icon, label, run, danger) => E("button", { class: "ctx-item" + (danger ? " danger" : ""), role: "menuitem", onclick: (e) => { closeMenu(); run(e); } }, AV.icon(icon, "ic-sm"), label);
    const sep = () => E("div", { class: "ctx-sep", role: "separator" });
    const quick = E("div", { class: "ctx-quick" }, ["👍", "❤️", "😂", "🔥"].map((char) => E("button", { class: "emoji-btn", title: "Reagir com " + char, onclick: () => { closeMenu(); react(m, char, true); } }, char)));
    menu = E("div", { class: "ctx-menu card", role: "menu", "aria-label": "Ações da mensagem", onkeydown: (e) => { if (e.key === "Escape") { e.stopPropagation(); closeMenu(); } } },
      quick,
      item("smile", "Adicionar reação", () => openPicker(m)),
      item("reply", "Responder", () => startReply(m)),
      mine && item("edit", "Editar mensagem", () => startEdit(m)),
      item("pin", m.pinned ? "Desafixar mensagem" : "Fixar mensagem", () => pinMessage(m, !(m.pinned > 0))),
      m.text && item("copy", "Copiar texto", () => navigator.clipboard.writeText(AV.md.plain(m.text, nameOf)).then(() => AV.toast("Texto copiado.", "ok"))),
      item("copy", "Copiar ID da mensagem", () => navigator.clipboard.writeText(m.id).then(() => AV.toast("ID copiado.", "ok"))),
      !mine && adminOn() && [sep(),
        item("clock", "Silenciar por 10 minutos", () => AV.admin("mute", { id: m.from, minutes: 10 }, nameOf(m.from) + " foi silenciado por 10 minutos.")),
        item("logout", "Expulsar " + nameOf(m.from), () => AV.admin("kick", { id: m.from }, nameOf(m.from) + " foi desconectado.")),
        item("ban", "Banir " + nameOf(m.from), () => AV.banDialog(m.from), true)],
      (mine || adminOn()) && [sep(), item("trash", "Apagar mensagem", (e) => remove(m, e.shiftKey), true)]);
    document.body.append(menu);
    const r = menu.getBoundingClientRect();
    menu.style.left = Math.max(8, Math.min(x, window.innerWidth - r.width - 8)) + "px";
    menu.style.top = Math.max(8, Math.min(y, window.innerHeight - r.height - 8)) + "px";
    const first = menu.querySelector(".ctx-item");
    if (first) first.focus();
  }

  /* ---- image viewer ---- */

  function lightbox(images, start) {
    let index = start;
    const img = E("img", { class: "lightbox-img", alt: "", draggable: "false" });
    const caption = E("div", { class: "truncate text-sm" });
    const show = () => { img.src = "/api/files/" + encodeURIComponent(images[index].id); img.alt = images[index].name; caption.textContent = images[index].name; };
    const step = (d) => { index = (index + d + images.length) % images.length; show(); };
    const close = () => { overlay.remove(); document.removeEventListener("keydown", onKey, true); };
    const onKey = (e) => {
      if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); close(); }
      if (e.key === "ArrowRight" && images.length > 1) step(1);
      if (e.key === "ArrowLeft" && images.length > 1) step(-1);
    };
    const overlay = E("div", { class: "overlay lightbox", role: "dialog", "aria-modal": "true", "aria-label": "Imagem", onmousedown: (e) => { if (e.target === overlay) close(); } },
      E("div", { class: "lightbox-bar" }, caption,
        E("button", { class: "btn", onclick: () => saveFile(images[index]) }, AV.icon("download", "ic-sm"), "Salvar"),
        E("button", { class: "btn btn-ghost btn-icon", "aria-label": "Fechar", onclick: close }, AV.icon("x"))),
      images.length > 1 && E("button", { class: "btn btn-icon lightbox-prev", "aria-label": "Imagem anterior", onclick: () => step(-1) }, "‹"),
      img,
      images.length > 1 && E("button", { class: "btn btn-icon lightbox-next", "aria-label": "Próxima imagem", onclick: () => step(1) }, "›"));
    show();
    document.addEventListener("keydown", onKey, true);
    document.getElementById("overlay-root").append(overlay);
  }

  /* ---- pinned messages: a panel inside the conversation column, above the messages ---- */

  async function pinMessage(m, on) {
    try {
      accept(await AV.api("PUT", "/api/messages/" + encodeURIComponent(m.id) + "/pin", { on }));
      AV.toast(on ? "Mensagem fixada." : "Mensagem desafixada.", "ok");
      loadPins();
    } catch (e) { AV.fail(e); }
  }

  async function loadPins() {
    const c = chat;
    try {
      const list = await AV.api("GET", "/api/pins?chat=" + encodeURIComponent(c));
      if (c !== chat) return;
      pinsList = list;
    } catch (e) {
      pinsList = { error: e.message };
    }
    renderPins();
  }

  function togglePins() {
    pinsOpen = !pinsOpen;
    renderPins();
    if (pinsOpen) loadPins();
    AV.renderHeaderSoon();
  }

  /* Always in sight, as in phone messengers: a strip with the latest pin; a click goes to it and moves to the next. */
  function pinStrip(host) {
    const list = Array.isArray(pinsList) ? pinsList : [];
    host.hidden = list.length === 0;
    if (!list.length) { host.replaceChildren(); return; }
    pinIndex = pinIndex % list.length;
    const m = list[pinIndex];
    const text = AV.md.plain(m.text || "", nameOf) || ((m.attachments || []).length ? "Anexo: " + m.attachments.map((a) => a.name).join(", ") : "");
    AV.fill(host, E("div", { class: "pin-strip" },
      list.length > 1 && E("div", { class: "pin-dots", "aria-hidden": "true" }, list.slice(0, 6).map((_, i) => E("i", { class: i === Math.min(pinIndex, 5) ? "on" : "" }))),
      E("button", { class: "pin-strip-main", title: "Ir para a mensagem fixada", onclick: () => { jumpTo(m.id, m.seq); pinIndex = (pinIndex + 1) % list.length; renderPins(); } },
        AV.icon("pin", "ic-sm text-accent"),
        E("div", { class: "min-w-0 flex-1" },
          E("div", { class: "pin-strip-label" }, "Mensagem fixada" + (list.length > 1 ? " · " + (pinIndex + 1) + " de " + list.length : "")),
          E("div", { class: "truncate text-sm" }, E("b", {}, nameOf(m.from) + ": "), text))),
      E("button", { class: "btn btn-ghost btn-sm", title: "Ver todas as mensagens fixadas", onclick: togglePins }, list.length > 1 ? "Ver todas" : "Ver")));
  }

  function renderPins() {
    const host = $("pins-panel");
    host.classList.toggle("pins-open", pinsOpen);
    if (!pinsOpen) { pinStrip(host); return; }
    host.hidden = false;
    const head = E("div", { class: "pins-head" }, AV.icon("pin", "ic-sm"), E("b", { class: "flex-1" }, "Mensagens fixadas"),
      E("button", { class: "btn btn-ghost btn-icon !h-7 !w-7", title: "Fechar", "aria-label": "Fechar mensagens fixadas", onclick: togglePins }, AV.icon("x", "ic-sm")));
    let body;
    if (!pinsList) body = E("div", { class: "chat-more" }, E("div", { class: "spinner", "aria-hidden": "true" }), "Carregando…");
    else if (pinsList.error) body = E("div", { class: "p-3 text-sm text-muted" }, pinsList.error);
    else if (!pinsList.length) body = E("div", { class: "p-4 text-sm text-muted" }, "Nenhuma mensagem fixada aqui. Para fixar, use o menu de uma mensagem (botão direito).");
    else {
      body = E("div", { class: "pins-list" }, pinsList.map((m) => E("div", { class: "pin-item" },
        AV.avatar(m.from, nameOf(m.from), "avatar-sm"),
        E("div", { class: "min-w-0 flex-1" },
          E("div", { class: "flex items-baseline gap-2" }, E("b", { class: "truncate" }, nameOf(m.from)), E("small", { class: "msg-time" }, AV.day(m.ts) + " às " + AV.time(m.ts))),
          E("div", { class: "pin-text" }, AV.md.plain(m.text || "", nameOf) || ((m.attachments || []).length ? "Anexo: " + m.attachments.map((a) => a.name).join(", ") : ""))),
        E("div", { class: "flex flex-none gap-1" },
          E("button", { class: "btn btn-sm", onclick: () => jumpTo(m.id, m.seq) }, "Ir"),
          E("button", { class: "btn btn-ghost btn-icon !h-7 !w-7", title: "Desafixar", "aria-label": "Desafixar mensagem", onclick: () => pinMessage(m, false) }, AV.icon("x", "ic-sm"))))));
    }
    AV.fill(host, head, body);
  }

  /* ---- live events ---- */

  function dropMessage(c, id) {
    const s = states.get(c);
    if (s) {
      s.msgs = s.msgs.filter((m) => m.id !== id);
      s.msgs.forEach((m) => { if (m.replyTo === id && m.ref) m.ref = { ...m.ref, missing: true }; });
    }
    rowCache.delete(id);
    if (editing === id) editing = null;
    if (reply && reply.id === id) reply = null;
    if (c === chat) { render(); renderComposer(); }
  }

  function onNew(m) {
    const s = states.get(m.chat);
    const here = m.chat === chat;
    if (!m.mine) {
      const t = typing.get(m.chat);
      if (t) t.delete(m.from);
    }
    if (m.mine) { accept(m); return; }
    if (s && s.loaded && !s.hasNewer) merge(s, [m]);
    if (here && !atBottom) newBelow++;
    const seen = here && atBottom && document.hasFocus() && !(s && s.hasNewer);
    if (!seen) {
      const u = AV.state.unread[m.chat] || { count: 0, mentions: 0 };
      AV.state.unread[m.chat] = { count: u.count + 1, mentions: u.mentions + ((m.mentions || []).includes(me().id) ? 1 : 0) };
      AV.renderNav();
      AV.sound("message");
    }
    if (here) { render(); markRead(); }
  }

  function onUpdate(m) {
    const listed = Array.isArray(pinsList) && pinsList.some((p) => p.id === m.id);
    if (m.chat === chat && (m.pinned > 0 || listed)) loadPins();
    states.forEach((s) => {
      const i = s.msgs.findIndex((x) => x.id === m.id);
      if (i >= 0) s.msgs[i] = m;
      s.msgs.forEach((x) => { if (x.replyTo === m.id && x.ref) x.ref = { ...x.ref, text: m.text }; });
    });
    if (states.has(chat)) render();
  }

  function onTyping({ chat: c, from, name }) {
    if (from === me().id) return;
    if (!typing.has(c)) typing.set(c, new Map());
    typing.get(c).set(from, { name, until: Date.now() + TYPING_SHOW_MS });
    if (c === chat) renderTyping();
    setTimeout(() => { if (c === chat) renderTyping(); }, TYPING_SHOW_MS + 50);
  }

  function wire() {
    AV.on("chat.new", onNew);
    AV.on("chat.update", onUpdate);
    AV.on("chat.delete", ({ chat: c, id }) => { dropMessage(c, id); if (c === chat && Array.isArray(pinsList) && pinsList.some((p) => p.id === id)) loadPins(); });
    AV.on("chat.cleared", ({ chat: c }) => { states.delete(c); pending.delete(c); if (c === chat) { render(); load(c); } });
    AV.on("chat.typing", onTyping);
    AV.on("chat.wiped", () => { states.clear(); pending.clear(); rowCache.clear(); AV.state.unread = {}; AV.renderNav(); if (chat) load(chat); });
    AV.on("chat.read", ({ chat: c }) => { delete AV.state.unread[c]; AV.renderNav(); });
    AV.on("chat.unread", ({ chats }) => { AV.state.unread = chats; AV.renderNav(); });
    AV.on("chat.muted", ({ until }) => { me().mutedUntil = until; renderComposer(); });
    AV.on("admin", (a) => { me().admin = a; rowCache.clear(); render(); });
    /* After a reconnection the server's copy wins: other conversations reload when opened, this one catches up now. */
    AV.on("net", ({ connected }) => {
      if (!connected) return;
      [...states.keys()].filter((c) => c !== chat).forEach((c) => states.delete(c));
      if (chat) refreshTail(chat);
      pendingOf(chat).filter((p) => p.status === "failed").forEach(deliver);
    });

    const c = $("composer");
    box().addEventListener("scroll", onScroll, { passive: true });
    /* Images and previews finish loading after the list is drawn: whoever was at the end stays at the end. */
    new ResizeObserver(() => { if (atBottom && chat && !state(chat).hasNewer) scrollBottom(); }).observe($("msg-list"));
    $("btn-send").addEventListener("click", send);
    $("btn-emoji").addEventListener("click", () => (panel && !panel.target ? closePicker() : openPicker(null)));
    $("btn-attach").addEventListener("click", () => $("file-input").click());
    $("file-input").addEventListener("change", (e) => { addFiles(e.target.files); e.target.value = ""; });
    c.addEventListener("input", () => { autosize(); updateSuggest(); sendTyping(); syncSendButton(); });
    AV.voice.bind($("btn-mic"), { box: $("composer-box"), strip: $("voice-strip"), onSend: sendVoice });
    c.addEventListener("click", updateSuggest);
    c.addEventListener("paste", (e) => {
      const list = [...(e.clipboardData ? e.clipboardData.files : [])];
      if (list.length) { e.preventDefault(); addFiles(list); }
    });
    c.addEventListener("keydown", (e) => {
      if (suggest) {
        if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); suggest.index = (suggest.index + (e.key === "ArrowDown" ? 1 : -1) + suggest.items.length) % suggest.items.length; renderSuggest(); return; }
        if (e.key === "Enter" || e.key === "Tab") { e.preventDefault(); pickSuggest(); return; }
        if (e.key === "Escape") { e.preventDefault(); suggest = null; renderSuggest(); return; }
      }
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); return; }
      if (e.key === "ArrowUp" && !c.value) {
        const mine = [...state(chat).msgs].reverse().find((m) => m.from === me().id && m.text);
        if (mine) { e.preventDefault(); startEdit(mine); }
        return;
      }
      if (e.key === "PageUp" || e.key === "PageDown") { e.preventDefault(); box().scrollBy({ top: (e.key === "PageUp" ? -1 : 1) * box().clientHeight * 0.85 }); return; }
      if (e.key === "Escape") {
        if (panel) { closePicker(); return; }
        if (reply) { cancelReply(); return; }
        jumpToPresent();
        markRead();
      }
    });
    const area = $("chat-pane");
    area.addEventListener("dragover", (e) => { if (e.dataTransfer && [...e.dataTransfer.types].includes("Files")) { e.preventDefault(); area.classList.add("dropping"); } });
    area.addEventListener("dragleave", (e) => { if (!area.contains(e.relatedTarget)) area.classList.remove("dropping"); });
    area.addEventListener("drop", (e) => { area.classList.remove("dropping"); if (e.dataTransfer && e.dataTransfer.files.length) { e.preventDefault(); addFiles(e.dataTransfer.files); } });
    document.addEventListener("mousedown", (e) => { if (menu && !menu.contains(e.target)) closeMenu(); });
    window.addEventListener("resize", closeMenu);
    window.addEventListener("focus", markRead);
  }

  AV.conv = {
    wire,
    select,
    render: () => { rowCache.clear(); render(); renderComposer(); },
    current: () => chat,
    togglePins,
    pinsOpen: () => pinsOpen,
    placeholder: (text) => { if (!(me().mutedUntil > Date.now())) $("composer").placeholder = text; },
    reset: (c) => { states.delete(c); pending.delete(c); },
  };
})();
