"use strict";
/* The application: reads state from the local server, renders it, and reacts to live events. */
(function () {
  const E = AV.el;
  const PAGE = 50;
  const EMOJIS = ["😀", "😁", "😂", "🤣", "😊", "😍", "😎", "🤔", "😅", "😉", "😢", "😭", "😡", "🥳", "😴", "🙄", "👍", "👎", "👏", "🙏", "💪", "👀", "🔥", "✨", "🎉", "🎮", "🎧", "☕", "🍕", "❤️", "💔", "💯", "✅", "❌", "⚠️", "🚀", "💡", "📌", "🎵", "👋"];
  const videoEls = new Map();
  let messages = [];
  let hasMore = false;
  let linkDown = false;
  let lastUpdateState = "";
  let activeFullscreenTile = null;

  AV.state = null;
  AV.chat = "radmin";

  const $ = (id) => document.getElementById(id);
  const me = () => AV.state.me;
  const setting = (key) => AV.state.settings[key];
  const flag = (key) => setting(key) === "true";
  const isDm = (chat) => chat.startsWith("dm:");
  const peerOf = (id) => AV.state.peers.find((p) => p.id === id);
  const groupOf = (id) => AV.state.groups.find((g) => g.id === id);
  const dmRoom = (a, b) => (a < b ? "dm:" + a + "+" + b : "dm:" + b + "+" + a);
  const roomOf = (chat) => (isDm(chat) ? dmRoom(me().id, chat.slice(3)) : chat);
  const chatOfRoom = (room) => (room.startsWith("dm:") ? "dm:" + room.slice(3).split("+").find((id) => id !== me().id) : room);
  const nameOf = (id) => (id === me().id ? me().name : (peerOf(id) || { name: "Participante" }).name);

  AV.refresh = async function () {
    AV.state = await AV.api("GET", "/api/state");
    document.documentElement.dataset.theme = setting("theme");
    renderAll();
    cleanStaleCalls();
  };
  const refreshSoon = AV.debounce(() => AV.refresh().catch(AV.fail), 200);

  function cleanStaleCalls() {
    Object.entries(AV.state.calls).forEach(([room, members]) => {
      if (members.includes(me().id) && AV.rtc.room !== room) AV.api("POST", "/api/call/state", { room, joined: false }).catch(() => {});
    });
  }

  AV.applySettings = function (key) {
    if (key === "theme") document.documentElement.dataset.theme = setting("theme");
    if (key.startsWith("audio.")) { AV.rtc.applyOutput(); AV.rtc.applyInput(); }
    if (key === "ui.lastChat") return;
    renderBottom();
  };

  function renderAll() {
    renderNav();
    renderHeader();
    renderMembers();
    renderCall();
    renderBottom();
    renderBanners();
  }

  function navItem(id, label, iconOrAvatar, unread, extra) {
    return E("button", { class: "nav-item", "aria-current": String(AV.chat === id), title: label, onclick: () => selectChat(id) },
      iconOrAvatar, E("span", { class: "hidden min-w-0 flex-1 truncate md:inline" }, label),
      unread > 0 && E("span", { class: "badge" }, unread > 99 ? "99+" : unread), extra);
  }

  function renderNav() {
    const nav = $("nav");
    const inCall = (chat) => (AV.state.calls[roomOf(chat)] || []).length > 0;
    const callMark = (chat) => (inCall(chat) ? AV.icon("volume", "ic-sm text-ok") : null);
    const network = AV.state.groups.filter((g) => g.kind === "radmin");
    const groups = AV.state.groups.filter((g) => g.kind !== "radmin");
    const peers = [...AV.state.peers].sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
    AV.fill(nav, 
      E("div", { class: "nav-title hidden md:block" }, "Rede"),
      network.map((g) => navItem(g.id, g.name, AV.icon("network"), g.unread, callMark(g.id))),
      E("div", { class: "nav-title hidden md:flex items-center justify-between" }, "Grupos",
        E("span", { class: "flex gap-1" },
          E("button", { class: "btn btn-ghost btn-icon !h-6 !w-6", title: "Entrar com um convite", "aria-label": "Entrar com um convite", onclick: joinGroupDialog }, AV.icon("lock", "ic-sm")),
          E("button", { class: "btn btn-ghost btn-icon !h-6 !w-6", title: "Criar grupo", "aria-label": "Criar grupo", onclick: createGroupDialog }, AV.icon("plus", "ic-sm")))),
      E("div", { class: "flex justify-center gap-1 md:hidden" },
        E("button", { class: "btn btn-ghost btn-icon", title: "Criar grupo", "aria-label": "Criar grupo", onclick: createGroupDialog }, AV.icon("plus"))),
      groups.map((g) => navItem(g.id, g.name, AV.icon("users"), g.unread, callMark(g.id))),
      E("div", { class: "nav-title hidden md:block" }, "Conversas privadas"),
      peers.length === 0 && E("div", { class: "hidden px-2 py-2 text-xs text-faint md:block" }, "Quando alguém entrar na rede, aparece aqui."),
      peers.map((p) => navItem("dm:" + p.id, p.name, AV.avatar(p.id, p.name, "avatar-sm", p.online), AV.state.directUnread["dm:" + p.id] || 0, callMark("dm:" + p.id))));
  }

  function chatTitle() {
    if (isDm(AV.chat)) {
      const p = peerOf(AV.chat.slice(3));
      return p ? p.name : "Conversa";
    }
    const g = groupOf(AV.chat);
    return g ? g.name : "Conversa";
  }

  function renderHeader() {
    const dm = isDm(AV.chat);
    const peer = dm ? peerOf(AV.chat.slice(3)) : null;
    const group = dm ? null : groupOf(AV.chat);
    const room = roomOf(AV.chat);
    const inThisCall = AV.rtc.room === room;
    AV.fill($("chat-title"), dm && peer ? AV.avatar(peer.id, peer.name, "avatar-sm mr-2 inline-grid align-middle", peer.online) : null,
      E("span", {}, chatTitle()),
      group && group.kind === "radmin" && E("span", { class: "chip ml-3 align-middle" }, E("span", { class: "dot " + (AV.state.radmin.connected ? "dot-on" : "dot-off") }), AV.state.radmin.connected ? "Radmin conectado" : "Sem Radmin"),
      dm && peer && E("span", { class: "chip ml-3 align-middle" }, peer.online ? "Online" : "Offline"));
    AV.fill($("chat-tools"), 
      group && group.hasInvite && E("button", { class: "btn", onclick: () => inviteDialog(group) }, AV.icon("copy", "ic-sm"), E("span", { class: "hidden lg:inline" }, "Convidar")),
      group && group.kind !== "radmin" && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Excluir grupo", "aria-label": "Excluir grupo", onclick: () => deleteGroup(group) }, AV.icon("trash")),
      dm && peer && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Limpar ou excluir conversa", "aria-label": "Limpar ou excluir conversa", onclick: () => dmOptionsDialog(peer) }, AV.icon("trash")),
      E("button", { class: "btn btn-ghost btn-icon xl:hidden", title: "Participantes", "aria-label": "Participantes", onclick: membersDialog }, AV.icon("users")),
      E("button", { class: "btn " + (inThisCall ? "btn-danger" : "btn-primary"), disabled: dm && peer && !peer.online && !inThisCall,
        onclick: () => (inThisCall ? AV.rtc.leave() : startCall(AV.chat)) },
      AV.icon(inThisCall ? "phone-off" : "phone", "ic-sm"), inThisCall ? "Sair da chamada" : "Chamar"));
    $("composer").placeholder = "Mensagem para " + (dm ? "" : "#") + chatTitle();
  }

  function memberRow(id, name, online, alias, duplicate) {
    return E("div", { class: "flex items-center gap-3 rounded-md px-2 py-1.5", dataset: { peer: id } },
      AV.avatar(id, name, "avatar-sm", online),
      E("div", { class: "min-w-0 flex-1" }, E("div", { class: "truncate font-semibold " + (online ? "" : "text-faint") }, name),
        E("div", { class: "truncate text-xs text-faint" }, (alias ? alias + " · " : "") + id.slice(0, 8))),
      duplicate && E("span", { class: "chip", title: "Outro participante usa o mesmo nome. Confira o código antes de confiar." }, AV.icon("alert", "ic-sm")),
      (AV.state.calls[roomOf(AV.chat)] || []).includes(id) && AV.icon("volume", "ic-sm text-ok"));
  }

  function membersList() {
    const group = isDm(AV.chat) ? null : groupOf(AV.chat);
    const others = AV.state.peers.filter((p) => (isDm(AV.chat) ? "dm:" + p.id === AV.chat : group && group.kind === "radmin" ? true : p.online && p.groups.includes(AV.chat)));
    const online = others.filter((p) => p.online);
    const offline = others.filter((p) => !p.online);
    const section = (title, list) => list.length ? [E("div", { class: "nav-title" }, title + " — " + list.length), list.map((p) => memberRow(p.id, p.name, p.online, p.alias, p.duplicateName))] : [];
    return [E("div", { class: "nav-title" }, "Você"), memberRow(me().id, me().name, true, me().alias, false), section("Online", online), section("Offline", offline)];
  }

  function renderMembers() {
    AV.fill($("members"), ...membersList().flat(2).filter(Boolean));
  }

  function membersDialog() {
    AV.modal("Participantes", (body) => body.append(...membersList().flat(2).filter(Boolean)), [{ label: "Fechar", run: (close) => close() }]);
  }

  function renderBanners() {
    const banners = [];
    const r = AV.state.radmin;
    if (!r.connected) {
      banners.push(E("div", { class: "banner error" }, AV.icon("alert"), E("div", { class: "flex-1" }, E("b", {}, "Radmin VPN indisponível. "), AV.state.issues.join(" ") + " Sem ele, só as conversas salvas ficam disponíveis."),
        E("button", { class: "btn", onclick: () => AV.openSettings("network") }, "Ver rede")));
    }
    if (linkDown) banners.push(E("div", { class: "banner" }, AV.icon("refresh"), "Reconectando ao aplicativo…"));
    const u = AV.state.update;
    if (u.state === "available") banners.push(E("div", { class: "banner info" }, AV.icon("download"), E("div", { class: "flex-1" }, "A versão " + u.available + " está disponível."), E("button", { class: "btn", onclick: () => AV.openSettings("update") }, "Ver detalhes")));
    if (u.state === "ready") banners.push(E("div", { class: "banner info" }, AV.icon("download"), E("div", { class: "flex-1" }, "A versão " + u.available + " está pronta."),
      E("button", { class: "btn btn-primary", onclick: () => AV.api("POST", "/api/update/install").catch(AV.fail) }, "Reiniciar para atualizar")));
    if (u.state === "downloading") banners.push(E("div", { class: "banner info" }, AV.icon("download"), "Baixando a versão " + u.available + " — " + Math.round(u.progress * 100) + "%"));
    if (u.state === "restarting" || u.state === "installing") banners.push(E("div", { class: "banner info" }, AV.icon("refresh"), "Atualizando o Angatu Voice. O aplicativo vai reiniciar sozinho."));
    if (u.state === "rolledback") banners.push(E("div", { class: "banner error" }, AV.icon("alert"), u.message));
    AV.fill($("banners"), ...banners);
  }

  function renderBottom() {
    const rtc = AV.rtc;
    const inCall = !!rtc.room;
    const r = AV.state.radmin;
    const btn = (icon, title, onclick, cls, disabled) => E("button", { class: "btn btn-icon " + (cls || ""), title, "aria-label": title, onclick, disabled: disabled || false }, AV.icon(icon));
    AV.fill($("bottombar"), 
      E("div", { class: "flex min-w-0 items-center gap-3" }, AV.avatar(me().id, me().name, "", true),
        E("div", { class: "hidden min-w-0 sm:block" }, E("div", { class: "truncate font-semibold" }, me().name),
          E("div", { class: "flex items-center gap-2 text-xs text-muted" }, E("span", { class: "dot " + (r.connected ? "dot-on" : "dot-off") }), r.connected ? "Radmin · " + r.ip : "Sem conexão"))),
      E("div", { class: "flex-1" }),
      E("div", { class: "flex items-center gap-2" },
        btn(rtc.muted ? "mic-off" : "mic", rtc.muted ? "Ativar microfone" : "Silenciar microfone", () => { rtc.toggleMute(); }, rtc.muted ? "btn-off" : ""),
        btn(rtc.deafened ? "headphones-off" : "headphones", rtc.deafened ? "Ativar áudio" : "Silenciar áudio", () => { rtc.toggleDeafen(); }, rtc.deafened ? "btn-off" : ""),
        btn("screen", rtc.localShare ? "Parar compartilhamento" : "Compartilhar tela", shareToggle, rtc.localShare ? "btn-live" : "", !inCall),
        inCall && E("button", { class: "btn btn-danger", onclick: () => rtc.leave() }, AV.icon("phone-off", "ic-sm"), E("span", { class: "hidden sm:inline" }, "Desligar")),
        btn("settings", "Configurações", () => AV.openSettings("audio"))));
  }

  async function shareToggle() {
    try {
      if (AV.rtc.localShare) AV.rtc.stopShare();
      else await AV.rtc.startShare();
    } catch (e) {
      if (e && e.name !== "NotAllowedError" && e.name !== "AbortError") AV.toast("Não foi possível compartilhar a tela.", "error");
    }
  }

  function videoEl(tile) {
    const key = (tile.local ? "local" : tile.id) + ":" + (tile.key || "");
    let node = videoEls.get(key);
    const shouldMute = tile.local || AV.rtc.isShareMuted(tile.id);
    if (!node || node.srcObject !== tile.stream) {
      node = E("video", { autoplay: true, playsinline: true, muted: shouldMute, "aria-label": "Tela de " + nameOf(tile.id) });
      node.muted = shouldMute;
      node.srcObject = tile.stream;
      videoEls.set(key, node);
    } else if (node.muted !== shouldMute) {
      node.muted = shouldMute;
    }
    return node;
  }

  function toggleFullscreen(tileKey) {
    if (activeFullscreenTile === tileKey) {
      activeFullscreenTile = null;
      if (document.fullscreenElement) {
        document.exitFullscreen().catch(() => {});
      }
    } else {
      activeFullscreenTile = tileKey;
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen?.().catch(() => {});
      }
    }
    renderCall();
  }

  function renderCall() {
    const panel = $("call-panel");
    const room = roomOf(AV.chat);
    const active = AV.state.calls[room] || [];
    const rtc = AV.rtc;
    const inThis = rtc.room === room;
    if (!inThis && active.length === 0 && !rtc.room) { panel.classList.add("hidden"); AV.fill(panel); return; }
    panel.classList.remove("hidden");
    if (!inThis) {
      const here = rtc.room ? "Você está em outra chamada." : active.map(nameOf).join(", ") + (active.length > 1 ? " estão" : " está") + " em chamada.";
      AV.fill(panel, E("div", { class: "flex items-center gap-3" }, AV.icon("volume", "text-ok"), E("div", { class: "flex-1" }, here),
        !rtc.room && E("button", { class: "btn btn-primary", onclick: () => startCall(AV.chat) }, "Entrar na chamada"),
        rtc.room && E("button", { class: "btn", onclick: () => selectChat(chatOfRoom(rtc.room)) }, "Ir para a chamada")));
      return;
    }
    const ids = [...new Set([me().id, ...active])];
    const tiles = ids.map((id) => E("div", { class: "tile" + (rtc.speaking[id] ? " speaking" : ""), dataset: { peer: id } },
      AV.avatar(id, nameOf(id), "avatar-lg"),
      E("div", { class: "tile-name" }, id === me().id ? "Você" : nameOf(id), id === me().id && rtc.muted && AV.icon("mic-off", "ic-sm ml-2 inline text-danger")),
      id !== me().id && E("input", { type: "range", min: 0, max: 200, value: rtc.peerVolume(id), class: "w-28", "aria-label": "Volume de " + nameOf(id),
        title: "Volume de " + nameOf(id), oninput: (e) => rtc.setPeerVolume(id, Number(e.target.value)) })));
    const videos = rtc.videoTiles().map((t) => {
      const tileKey = (t.local ? "local" : t.id) + ":" + (t.key || "");
      const isFullscreen = activeFullscreenTile === tileKey;
      const isMuted = rtc.isShareMuted(t.id);
      return E("div", {
        class: "tile tile-video" + (isFullscreen ? " fullscreen" : ""),
        dataset: { tile: tileKey },
        ondblclick: () => toggleFullscreen(tileKey)
      },
        E("div", { class: "tile-video-header" },
          E("div", { class: "tile-name truncate" }, (t.local ? "Sua tela" : "Tela de " + nameOf(t.id)) + (t.local ? qualityLabel() : "")),
          E("div", { class: "flex items-center gap-1" },
            E("button", {
              class: "btn btn-ghost btn-icon !h-7 !w-7 hover:bg-bg2",
              title: isMuted ? "Reativar som da transmissão" : "Silenciar transmissão de tela",
              "aria-label": isMuted ? "Reativar som da transmissão" : "Silenciar transmissão de tela",
              onclick: (e) => { e.stopPropagation(); rtc.toggleShareMute(t.id); }
            }, AV.icon(isMuted ? "volume-x" : "volume", "ic-sm")),
            E("button", {
              class: "btn btn-ghost btn-icon !h-7 !w-7 hover:bg-bg2",
              title: isFullscreen ? "Sair da tela cheia (Esc)" : "Abrir transmissão em tela cheia",
              "aria-label": isFullscreen ? "Sair da tela cheia (Esc)" : "Abrir transmissão em tela cheia",
              onclick: (e) => { e.stopPropagation(); toggleFullscreen(tileKey); }
            }, AV.icon(isFullscreen ? "minimize" : "maximize", "ic-sm"))
          )
        ),
        videoEl(t)
      );
    });
    AV.fill(panel, E("div", { class: "grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4" }, videos, tiles));
    for (const key of [...videoEls.keys()]) {
      if (!rtc.videoTiles().some((t) => (t.local ? "local" : t.id) + ":" + (t.key || "") === key)) videoEls.delete(key);
    }
  }

  function qualityLabel() {
    const q = AV.rtc.quality();
    return q.level === 0 ? " · qualidade máxima" : " · qualidade reduzida (" + q.fps + " FPS)";
  }

  async function startCall(chat) {
    try {
      await AV.rtc.join(roomOf(chat));
      if (AV.chat !== chat) selectChat(chat);
    } catch (e) {
      AV.fail(e);
    }
  }

  AV.rtc.onchange = () => { renderCall(); renderBottom(); renderHeader(); renderNav(); renderMembers(); };
  AV.rtc.onspeaking = (id, value) => {
    document.querySelectorAll('[data-peer="' + id + '"]').forEach((n) => n.classList.toggle("speaking", value));
    pushOverlay();
  };

  let lastOverlay = "";
  const pushOverlay = AV.debounce(() => {
    const speaking = AV.rtc.room ? Object.entries(AV.rtc.speaking).filter(([, on]) => on).map(([id]) => ({ id, name: nameOf(id) })) : [];
    const key = JSON.stringify(speaking);
    if (key === lastOverlay) return;
    lastOverlay = key;
    AV.api("POST", "/api/overlay", { speaking }).catch(() => {});
  }, 60);

  function createGroupDialog() {
    const input = E("input", { class: "input", maxlength: 40, placeholder: "Nome do grupo", "aria-label": "Nome do grupo" });
    AV.modal("Criar grupo", (body) => body.append(E("label", { class: "label" }, "Nome"), input,
      E("p", { class: "mt-3 text-sm text-muted" }, "Só entra quem tiver o convite do grupo. Você poderá copiá-lo depois.")), [
      { label: "Cancelar", run: (close) => close() },
      { label: "Criar", primary: true, run: async (close) => {
        try {
          const created = await AV.api("POST", "/api/groups", { name: input.value });
          close();
          await AV.refresh();
          selectChat(created.id);
        } catch (e) { AV.fail(e); }
      } }]);
  }

  function joinGroupDialog() {
    const input = E("input", { class: "input mono", placeholder: "AV1.…", "aria-label": "Código do convite" });
    AV.modal("Entrar com um convite", (body) => body.append(E("label", { class: "label" }, "Código do convite"), input), [
      { label: "Cancelar", run: (close) => close() },
      { label: "Entrar", primary: true, run: async (close) => {
        try {
          const joined = await AV.api("POST", "/api/groups/join", { code: input.value.trim() });
          close();
          await AV.refresh();
          selectChat(joined.id);
        } catch (e) { AV.fail(e); }
      } }]);
  }

  async function inviteDialog(group) {
    try {
      const { code } = await AV.api("GET", "/api/groups/" + group.id + "/invite");
      AV.modal("Convite para " + group.name, (body) => body.append(
        E("p", { class: "mb-3 text-sm text-muted" }, "Envie este código só para quem deve participar. Quem o tiver entra no grupo."),
        E("textarea", { class: "input mono selectable", rows: 4, readonly: true, "aria-label": "Código do convite" }, code)), [
        { label: "Fechar", run: (close) => close() },
        { label: "Copiar código", primary: true, run: async (close) => { await navigator.clipboard.writeText(code); AV.toast("Código copiado.", "ok"); close(); } }]);
    } catch (e) { AV.fail(e); }
  }

  function dmOptionsDialog(peer) {
    AV.modal("Conversa com " + peer.name, (body, close) => {
      body.append(
        E("p", { class: "mb-4 text-sm text-muted" }, "Escolha o que deseja fazer com esta conversa privada:"),
        E("div", { class: "flex flex-col gap-3" },
          E("div", { class: "card flex items-center justify-between p-3" },
            E("div", { class: "pr-3" },
              E("div", { class: "font-semibold text-sm" }, "Limpar mensagens"),
              E("div", { class: "text-xs text-muted" }, "Apaga todo o histórico de mensagens trocadas com este participante no seu computador.")),
            E("button", { class: "btn btn-danger btn-sm whitespace-nowrap", onclick: async () => {
              close();
              if (await AV.confirm("Limpar mensagens de " + peer.name + "?", "Todas as mensagens desta conversa serão apagadas permanentemente deste computador.", "Limpar mensagens", true)) {
                try {
                  await AV.api("DELETE", "/api/messages?chat=" + encodeURIComponent("dm:" + peer.id));
                  messages = [];
                  renderMessages(true);
                  AV.toast("Mensagens apagadas.", "ok");
                } catch (e) { AV.fail(e); }
              }
            } }, "Limpar")),
          E("div", { class: "card flex items-center justify-between p-3" },
            E("div", { class: "pr-3" },
              E("div", { class: "font-semibold text-sm" }, "Excluir conversa"),
              E("div", { class: "text-xs text-muted" }, "Apaga todas as mensagens e remove o participante da sua lista lateral.")),
            E("button", { class: "btn btn-danger btn-sm whitespace-nowrap", onclick: async () => {
              close();
              if (await AV.confirm("Excluir conversa com " + peer.name + "?", "A conversa e todas as mensagens serão apagadas permanentemente deste computador.", "Excluir conversa", true)) {
                try {
                  await AV.api("DELETE", "/api/peers/" + peer.id);
                  messages = [];
                  AV.chat = "radmin";
                  await AV.refresh();
                  selectChat("radmin");
                  AV.toast("Conversa excluída.", "ok");
                } catch (e) { AV.fail(e); }
              }
            } }, "Excluir"))
        )
      );
    }, [{ label: "Fechar", run: (close) => close() }]);
  }

  async function deleteGroup(group) {
    if (!(await AV.confirm("Excluir " + group.name + "?", "Você sairá do grupo e todas as mensagens dele serão apagadas deste computador.", "Excluir grupo", true))) return;
    try {
      await AV.api("DELETE", "/api/groups/" + group.id);
      AV.chat = "radmin";
      await AV.refresh();
      selectChat("radmin");
      AV.toast("Grupo excluído com sucesso.", "ok");
    } catch (e) { AV.fail(e); }
  }

  /* ---- messages ---- */

  async function selectChat(chat) {
    AV.chat = chat;
    messages = [];
    renderAll();
    await loadMessages();
    AV.api("PUT", "/api/settings", { "ui.lastChat": chat }).catch(() => {});
    $("composer").focus();
  }

  async function loadMessages() {
    const chat = AV.chat;
    try {
      const page = await AV.api("GET", "/api/messages?chat=" + encodeURIComponent(chat) + "&limit=" + PAGE);
      if (chat !== AV.chat) return;
      messages = page;
      hasMore = page.length === PAGE;
      renderMessages(true);
      markRead();
    } catch (e) { AV.fail(e); }
  }

  async function loadOlder() {
    if (!messages.length) return;
    const chat = AV.chat;
    const page = await AV.api("GET", "/api/messages?chat=" + encodeURIComponent(chat) + "&limit=" + PAGE + "&before=" + messages[0].ts).catch(AV.fail);
    if (!page || chat !== AV.chat) return;
    hasMore = page.length === PAGE;
    messages = [...page, ...messages];
    const box = $("messages");
    const before = box.scrollHeight;
    renderMessages(false);
    box.scrollTop = box.scrollHeight - before;
  }

  function markRead() {
    if (!document.hasFocus()) return;
    const chat = AV.chat;
    const g = groupOf(chat);
    const unread = g ? g.unread : AV.state.directUnread[chat] || 0;
    if (unread === 0) return;
    if (g) g.unread = 0; else delete AV.state.directUnread[chat];
    renderNav();
    AV.api("POST", "/api/messages/read", { chat }).catch(() => {});
  }

  function linkify(text) {
    const parts = text.split(/(https?:\/\/[^\s<>"']+)/g);
    return parts.map((part, i) => (i % 2 === 1
      ? E("a", { tabindex: 0, role: "link", title: "Abrir no navegador", onclick: () => openLink(part), onkeydown: (e) => { if (e.key === "Enter") openLink(part); } }, part)
      : part));
  }

  async function openLink(url) {
    if (await AV.confirm("Abrir este link?", url, "Abrir no navegador")) AV.api("POST", "/api/system/open", { url }).catch(AV.fail);
  }

  function messageNode(m, prev) {
    const sameDay = prev && new Date(prev.ts).toDateString() === new Date(m.ts).toDateString();
    const compact = prev && sameDay && prev.from === m.from && m.ts - prev.ts < 300000;
    const body = E("div", { class: "min-w-0 flex-1" },
      !compact && E("div", { class: "msg-head" }, E("span", { class: "msg-name", style: "color:" + AV.colorFor(m.from) }, m.name), E("span", { class: "msg-time" }, AV.time(m.ts)),
        m.pending && E("span", { class: "msg-time" }, "· aguardando entrega")),
      E("div", { class: "msg-text selectable" }, linkify(m.text)));
    return E("div", { class: "msg" + (compact ? " msg-compact" : "") + (m.pending ? " msg-pending" : ""), dataset: { id: m.id } },
      compact ? E("div", { class: "w-9 flex-none" }) : AV.avatar(m.from, m.name), body);
  }

  function renderMessages(stickBottom) {
    const box = $("messages");
    const nodes = [];
    if (hasMore) nodes.push(E("div", { class: "flex justify-center py-2" }, E("button", { class: "btn btn-ghost", onclick: loadOlder }, "Carregar mensagens anteriores")));
    if (messages.length === 0) {
      nodes.push(E("div", { class: "empty" }, AV.icon("chat", "!h-8 !w-8"), E("div", { class: "font-semibold text-ink" }, "Nenhuma mensagem ainda"), E("div", {}, "Diga olá para começar a conversa.")));
    }
    messages.forEach((m, i) => {
      const prev = messages[i - 1];
      if (!prev || new Date(prev.ts).toDateString() !== new Date(m.ts).toDateString()) nodes.push(E("div", { class: "day-sep" }, AV.day(m.ts)));
      nodes.push(messageNode(m, prev));
    });
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 120;
    AV.fill(box, ...nodes);
    if (stickBottom || nearBottom) box.scrollTop = box.scrollHeight;
  }

  function onMessage(m) {
    if (m.chat === AV.chat) {
      if (!messages.some((x) => x.id === m.id)) { messages.push(m); renderMessages(false); }
      if (!m.mine) markRead();
    }
    if (!m.mine && (m.chat !== AV.chat || !document.hasFocus())) { refreshSoon(); AV.sound("message"); }
  }

  async function send() {
    const box = $("composer");
    const text = box.value.trim();
    if (!text) return;
    box.value = "";
    autosize();
    try {
      const m = await AV.api("POST", "/api/messages", { chat: AV.chat, text });
      if (m.chat === AV.chat && !messages.some((x) => x.id === m.id)) { messages.push(m); renderMessages(true); }
    } catch (e) {
      box.value = text;
      AV.fail(e);
    }
  }

  function autosize() {
    const box = $("composer");
    box.style.height = "auto";
    box.style.height = Math.min(box.scrollHeight, 140) + "px";
  }

  function emojiPicker() {
    const existing = document.getElementById("emoji-pop");
    if (existing) { existing.remove(); return; }
    const pop = E("div", { id: "emoji-pop", class: "card fixed bottom-24 left-4 z-30 grid grid-cols-8 gap-1 p-2 md:left-72" },
      EMOJIS.map((emoji) => E("button", { class: "btn btn-ghost btn-icon text-lg", type: "button", "aria-label": "Inserir " + emoji,
        onclick: () => { const c = $("composer"); c.setRangeText(emoji, c.selectionStart, c.selectionEnd, "end"); c.focus(); autosize(); } }, emoji)));
    document.body.append(pop);
    setTimeout(() => document.addEventListener("click", function close(e) {
      if (!pop.contains(e.target) && e.target.closest("#btn-emoji") === null) { pop.remove(); document.removeEventListener("click", close); }
      else if (!document.body.contains(pop)) document.removeEventListener("click", close);
    }), 0);
  }

  /* ---- live events ---- */

  function onHotkey({ action, state }) {
    const rtc = AV.rtc;
    if (action === "ptt") { rtc.setPtt(state === "down"); return; }
    if (state !== "down") return;
    if (action === "mic") { rtc.toggleMute(); AV.toast(rtc.muted ? "Microfone silenciado." : "Microfone ativado."); }
    if (action === "deafen") { rtc.toggleDeafen(); AV.toast(rtc.deafened ? "Áudio silenciado." : "Áudio ativado."); }
    if (action === "muteAll") { rtc.toggleMuteAll(); AV.toast(rtc.muteAll ? "Todos silenciados." : "Todos reativados."); }
    if (action === "call") { if (rtc.room) rtc.leave(); else startCall(AV.chat); }
    if (action === "share") {
      if (!rtc.room) { AV.toast("Entre em uma chamada para compartilhar a tela."); return; }
      shareToggle();
    }
  }

  function onUpdate(update) {
    const sameState = lastUpdateState === update.state;
    lastUpdateState = update.state;
    AV.state.update = update;
    renderBanners();
    if (sameState && update.state === "downloading") {
      const bar = document.querySelector(".progress > i");
      if (bar) bar.style.width = Math.round(update.progress * 100) + "%";
    } else {
      AV.refreshSettings();
    }
  }

  function wire() {
    AV.on("open", () => { linkDown = false; AV.refresh().then(loadMessages).catch(AV.fail); });
    AV.on("link", (up) => { if (!up) { linkDown = true; renderBanners(); } });
    AV.on("expired", () => AV.modal("Sessão encerrada", (b) => b.append(E("p", { class: "text-muted" }, "Feche e abra o Angatu Voice de novo para continuar.")), []));
    AV.on("message", onMessage);
    AV.on("message.delivered", ({ id }) => { const m = messages.find((x) => x.id === id); if (m) { m.pending = false; renderMessages(false); } });
    AV.on("peer.online", ({ name }) => { AV.toast(name + " entrou na rede."); refreshSoon(); });
    AV.on("peer.offline", ({ name }) => { AV.toast(name + " saiu da rede."); refreshSoon(); });
    AV.on("net", () => refreshSoon());
    AV.on("group.changed", () => refreshSoon());
    AV.on("peer.profile", () => refreshSoon());
    AV.on("messages.cleared", ({ chat }) => { if (chat === AV.chat) { messages = []; renderMessages(true); } });
    AV.on("peer.removed", ({ id }) => { if (AV.chat === "dm:" + id) { selectChat("radmin"); } refreshSoon(); });
    AV.on("call.state", (d) => { AV.rtc.handleRoom(d); renderCall(); renderNav(); renderMembers(); renderHeader(); });
    AV.on("call.signal", (d) => AV.rtc.handleSignal(d));
    AV.on("update", onUpdate);
    AV.on("hotkey", onHotkey);
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && activeFullscreenTile) {
        activeFullscreenTile = null;
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
        renderCall();
      }
    });
    document.addEventListener("fullscreenchange", () => {
      if (!document.fullscreenElement && activeFullscreenTile) {
        activeFullscreenTile = null;
        renderCall();
      }
    });
    $("btn-open-settings").addEventListener("click", () => AV.openSettings("app"));
    $("btn-send").addEventListener("click", send);
    $("btn-emoji").addEventListener("click", emojiPicker);
    $("composer").addEventListener("input", autosize);
    $("composer").addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
    });
    window.addEventListener("focus", markRead);
  }

  async function init() {
    try {
      await AV.refresh();
      wire();
      lastUpdateState = AV.state.update.state;
      const last = setting("ui.lastChat");
      const valid = last === "radmin" || groupOf(last) || (isDm(last) && peerOf(last.slice(3)));
      await selectChat(valid ? last : "radmin");
      AV.live.start();
      document.getElementById("boot").classList.add("done");
    } catch (e) {
      document.getElementById("boot-text").textContent = "Não foi possível carregar o aplicativo. Feche e abra o Angatu Voice de novo.";
      document.querySelector(".boot-bar").remove();
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
