"use strict";
/* The application: reads state from the local server, renders it, and reacts to live events. */
(function () {
  const E = AV.el;
  const LATENCY_STALE_MS = 8000;
  const CALL_RTT_STALE_MS = 6000;
  let callView = null;
  let focusedTile = null;
  let chatOverStage = false;
  let incomingKey = "";
  let linkDown = false;
  let lastUpdateState = "";
  let activeFullscreenTile = null;

  AV.state = null;
  AV.chat = "publico";
  AV.latency = null;

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
  const adminOn = () => !!(me().admin && me().admin.active);

  AV.refresh = async function () {
    AV.state = await AV.api("GET", "/api/state");
    AV.latency = { peers: Object.fromEntries(AV.state.peers.map((p) => [p.id, p.rtt])), at: Date.now() };
    document.documentElement.dataset.theme = setting("theme");
    renderAll();
    cleanStaleCalls();
  };
  const refreshSoon = AV.debounce(() => AV.refresh().catch(AV.fail), 200);
  const CONNECT_GRACE_MS = 6000;
  setTimeout(() => { if (AV.state) refreshSoon(); }, CONNECT_GRACE_MS + 100);

  function cleanStaleCalls() {
    Object.entries(AV.state.calls).forEach(([room, members]) => {
      if (members.includes(me().id) && AV.rtc.room !== room) AV.api("POST", "/api/call/state", { room, joined: false }).catch(() => {});
    });
  }

  AV.applySettings = function (key) {
    if (key === "theme") document.documentElement.dataset.theme = setting("theme");
    if (key.startsWith("audio.")) { AV.rtc.applyOutput(); AV.rtc.applyInput(); }
    if (key === "audio.input") AV.rtc.switchInput();
    if (key.startsWith("video.")) AV.rtc.applyVideo();
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
    renderIncoming();
  }

  /* Mentions get a count in their own colour; other unread messages get a plain count and a brighter name. */
  function navItem(id, label, iconOrAvatar, extra) {
    const u = (AV.state.unread || {})[id];
    const count = u ? (u.mentions || u.count) : 0;
    return E("button", { class: "nav-item" + (u && u.count ? " nav-unread" : ""), "aria-current": String(AV.chat === id), title: label + (u && u.count ? " (" + u.count + " não lidas)" : ""), onclick: () => selectChat(id) },
      iconOrAvatar, E("span", { class: "hidden min-w-0 flex-1 truncate md:inline" }, label,
        AV.conv.typingIn(id).length > 0 && E("span", { class: "nav-typing", title: "Digitando" }, E("span", { class: "typing-dots", "aria-hidden": "true" }, E("i"), E("i"), E("i")), "digitando")),
      count > 0 && E("span", { class: "badge" + (u.mentions ? " badge-mention" : "") }, count >= 100 ? "99+" : count), extra);
  }

  function renderNav() {
    const nav = $("nav");
    const inCall = (chat) => (AV.state.calls[roomOf(chat)] || []).length > 0;
    const callMark = (chat) => (inCall(chat) ? AV.icon("volume", "ic-sm text-ok") : null);
    const network = AV.state.groups.filter((g) => g.kind === "public");
    const groups = AV.state.groups.filter((g) => g.kind !== "public");
    const peers = [...AV.state.peers].sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
    AV.fill(nav, 
      E("div", { class: "nav-title hidden md:block" }, "Rede"),
      network.map((g) => navItem(g.id, g.name, AV.icon("network"), callMark(g.id))),
      E("div", { class: "nav-title hidden md:flex items-center justify-between" }, "Grupos",
        E("span", { class: "flex gap-1" },
          E("button", { class: "btn btn-ghost btn-icon !h-6 !w-6", title: "Entrar com um convite", "aria-label": "Entrar com um convite", onclick: joinGroupDialog }, AV.icon("lock", "ic-sm")),
          E("button", { class: "btn btn-ghost btn-icon !h-6 !w-6", title: "Criar grupo", "aria-label": "Criar grupo", onclick: createGroupDialog }, AV.icon("plus", "ic-sm")))),
      E("div", { class: "flex justify-center gap-1 md:hidden" },
        E("button", { class: "btn btn-ghost btn-icon", title: "Criar grupo", "aria-label": "Criar grupo", onclick: createGroupDialog }, AV.icon("plus"))),
      groups.map((g) => navItem(g.id, g.name, AV.icon("users"), callMark(g.id))),
      E("div", { class: "nav-title hidden md:block" }, "Conversas privadas"),
      peers.length === 0 && E("div", { class: "hidden px-2 py-2 text-xs text-faint md:block" }, "Quando alguém entrar na rede, aparece aqui."),
      peers.map((p) => navItem("dm:" + p.id, p.name, AV.avatar(p.id, p.name, "avatar-sm", p.online), callMark("dm:" + p.id))));
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
      group && group.kind === "public" && E("span", { class: "chip ml-3 hidden align-middle sm:inline-flex" }, E("span", { class: "dot " + (AV.state.net.connected ? "dot-on" : "dot-off") }), AV.state.net.connected ? "Conectado" : "Sem conexão"),
      dm && peer && (AV.conv.typingIn(AV.chat).length
        ? E("span", { class: "chip chip-typing ml-3 hidden align-middle sm:inline-flex" }, E("span", { class: "typing-dots", "aria-hidden": "true" }, E("i"), E("i"), E("i")), "digitando…")
        : E("span", { class: "chip ml-3 hidden align-middle sm:inline-flex" }, peer.online ? "Online" : "Offline")));
    AV.fill($("chat-tools"), 
      group && group.hasInvite && E("button", { class: "btn", onclick: () => inviteDialog(group) }, AV.icon("copy", "ic-sm"), E("span", { class: "hidden lg:inline" }, "Convidar")),
      adminOn() && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Apagar todas as mensagens desta conversa", "aria-label": "Apagar todas as mensagens desta conversa", onclick: clearChat }, AV.icon("eraser")),
      group && group.kind !== "public" && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Sair do grupo", "aria-label": "Sair do grupo", onclick: () => deleteGroup(group) }, AV.icon("trash")),
      dm && peer && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Remover da lista", "aria-label": "Remover da lista", onclick: () => removePeer(peer) }, AV.icon("trash")),
      E("button", { class: "btn btn-ghost btn-icon" + (AV.conv.pinsOpen() ? " btn-on" : ""), title: "Mensagens fixadas", "aria-label": "Mensagens fixadas", "aria-pressed": String(AV.conv.pinsOpen()), onclick: () => AV.conv.togglePins() }, AV.icon("pin")),
      E("button", { class: "btn btn-ghost btn-icon", title: document.fullscreenElement ? "Sair da tela cheia (Esc)" : "Tela cheia", "aria-label": document.fullscreenElement ? "Sair da tela cheia" : "Tela cheia", onclick: toggleChatFullscreen }, AV.icon(document.fullscreenElement ? "minimize" : "maximize")),
      E("button", { id: "stage-chat", class: "btn hidden", title: chatOverStage ? "Ocultar conversa" : "Mostrar conversa", "aria-label": chatOverStage ? "Ocultar conversa" : "Mostrar conversa", onclick: toggleChatOverStage }, AV.icon("chat", "ic-sm"), E("span", { class: "hidden lg:inline" }, chatOverStage ? "Ocultar conversa" : "Mostrar conversa")),
      E("button", { class: "btn btn-ghost btn-icon xl:hidden", title: "Participantes", "aria-label": "Participantes", onclick: membersDialog }, AV.icon("users")),
      E("button", { class: "btn " + (inThisCall ? "btn-danger" : "btn-primary"), title: inThisCall ? "Sair da chamada" : "Chamar", "aria-label": inThisCall ? "Sair da chamada" : "Chamar", disabled: dm && peer && !peer.online && !inThisCall,
        onclick: () => (inThisCall ? AV.rtc.leave() : startCall(AV.chat)) },
      AV.icon(inThisCall ? "phone-off" : "phone", "ic-sm"), E("span", { class: "hidden sm:inline" }, inThisCall ? "Sair da chamada" : "Chamar")));
    AV.conv.placeholder("Mensagem para " + (dm ? "" : "#") + chatTitle());
  }

  /* The whole interface goes full screen, not a box drawn inside it; Esc is the browser's own way out. */
  function toggleChatFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else document.documentElement.requestFullscreen().catch(() => AV.toast("Não foi possível entrar em tela cheia."));
  }

  async function clearChat() {
    if (!(await AV.confirm("Apagar todas as mensagens de " + chatTitle() + "?", "Elas somem para todos e não podem ser recuperadas.", "Apagar tudo", true))) return;
    try {
      const r = await AV.api("POST", "/api/admin/clear", { chat: AV.chat });
      AV.toast((r && r.removed ? r.removed : 0) + " mensagens apagadas.", "ok");
    } catch (e) { AV.fail(e); }
  }

  function toggleChatOverStage() {
    chatOverStage = !chatOverStage;
    renderHeader();
    renderCall();
  }

  function memberRow(id, name, online, duplicate) {
    const self = id === me().id;
    return E("div", { class: "member-row flex items-center gap-3 rounded-md px-2 py-1.5" + (AV.rtc.speaking[id] ? " speaking" : ""), dataset: { peer: id } },
      AV.avatar(id, name, "avatar-sm", online),
      E("div", { class: "min-w-0 flex-1" }, E("div", { class: "truncate font-semibold " + (online ? "" : "text-faint") }, name),
        E("div", { class: "truncate text-xs text-faint" }, id.slice(0, 8))),
      duplicate && E("span", { class: "chip", title: "Outro participante usa o mesmo nome. Confira o código antes de confiar." }, AV.icon("alert", "ic-sm")),
      !self && online && E("span", { dataset: { pingPeer: id } }, AV.pingBadge(peerPing(id))),
      !self && AV.rtc.isPeerMuted(id) && E("span", { title: "Silenciado por você" }, AV.icon("volume-x", "ic-sm text-danger")),
      (AV.state.calls[roomOf(AV.chat)] || []).includes(id) && AV.icon("volume", "ic-sm text-ok"));
  }

  function membersList() {
    const group = isDm(AV.chat) ? null : groupOf(AV.chat);
    const others = AV.state.peers.filter((p) => (isDm(AV.chat) ? "dm:" + p.id === AV.chat : group && group.kind === "public" ? true : p.online && p.groups.includes(AV.chat)));
    const online = others.filter((p) => p.online);
    const offline = others.filter((p) => !p.online);
    const section = (title, list) => list.length ? [E("div", { class: "nav-title" }, title + " — " + list.length), list.map((p) => memberRow(p.id, p.name, p.online, p.duplicateName))] : [];
    return [E("div", { class: "nav-title" }, "Você"), memberRow(me().id, me().name, true, false), section("Online", online), section("Offline", offline)];
  }

  function renderMembers() {
    AV.fill($("members"), ...membersList().flat(2).filter(Boolean));
  }

  function membersDialog() {
    AV.modal("Participantes", (body) => body.append(...membersList().flat(2).filter(Boolean)), [{ label: "Fechar", run: (close) => close() }]);
  }

  function renderBanners() {
    const banners = [];
    const r = AV.state.net;
    // The connection takes a moment after opening: that is not an outage worth a red banner.
    if (!r.connected && performance.now() > CONNECT_GRACE_MS) {
      banners.push(E("div", { class: "banner error" }, AV.icon("alert"), E("div", { class: "flex-1" }, E("b", {}, "Sem conexão com o servidor. "), "Tentando de novo. As mensagens ficam no servidor e aparecem assim que a conexão voltar."),
        E("button", { class: "btn", onclick: () => AV.openSettings("network") }, "Ver rede")));
    }
    if (linkDown) banners.push(E("div", { class: "banner" }, AV.icon("refresh"), "Reconectando ao aplicativo…"));
    callsElsewhere().forEach(([room, members]) => banners.push(E("div", { class: "banner info" }, AV.icon("phone"),
      E("div", { class: "min-w-0 flex-1 truncate" }, members.map(nameOf).join(", ") + (members.length > 1 ? " estão" : " está") + " em chamada em " + roomName(room) + "."),
      E("button", { class: "btn", onclick: () => selectChat(chatOfRoom(room)) }, "Ver"),
      E("button", { class: "btn btn-primary", onclick: () => startCall(chatOfRoom(room)) }, "Entrar"))));
    const u = AV.state.update;
    if (u.state === "rolledback") banners.push(E("div", { class: "banner error" }, AV.icon("alert"), u.message));
    AV.fill($("banners"), ...banners);
  }

  function roomName(room) {
    if (room.startsWith("dm:")) return "uma conversa privada";
    const group = groupOf(room);
    return group ? group.name : "um grupo";
  }

  function callsElsewhere() {
    return Object.entries(AV.state.calls).filter(([room, members]) => members.length > 0 && !members.includes(me().id)
      && room !== roomOf(AV.chat) && !(AV.state.incoming || []).some((c) => c.room === room));
  }

  /* ---- latency ---- */

  function peerPing(id) {
    const stats = AV.rtc.peerStats(id);
    if (stats && stats.rtt !== null && performance.now() - stats.at < CALL_RTT_STALE_MS && AV.rtc.connectionState(id) === "connected") return stats.rtt;
    const fresh = AV.latency && Date.now() - AV.latency.at < LATENCY_STALE_MS;
    const value = fresh ? AV.latency.peers[id] : undefined;
    return value === undefined || value < 0 ? null : value;
  }

  function networkPing() {
    if (!AV.state.net.connected) return { ms: null, text: "Sem rede", title: "Sem conexão com o servidor, não há como medir a latência." };
    const room = AV.rtc.room;
    const ids = room ? (AV.state.calls[room] || []).filter((id) => id !== me().id) : AV.state.peers.filter((p) => p.online).map((p) => p.id);
    if (!ids.length) return { ms: null, text: "—", title: "Ninguém conectado para medir a latência." };
    const samples = ids.map(peerPing).filter((v) => v !== null).sort((a, b) => a - b);
    if (!samples.length) return { ms: null, text: "Sem medição", title: "A latência não pôde ser medida agora. A conexão pode estar instável." };
    const ms = samples[Math.floor(samples.length / 2)];
    return { ms, text: ms + " ms", title: "Latência " + AV.pingWord(ms) + ": " + ms + " ms até " + (room ? "a chamada" : "a rede") + ". Clique para ver por pessoa." };
  }

  function pingChip() {
    const ping = networkPing();
    return E("button", { id: "ping-chip", class: "ping-chip", title: ping.title, "aria-label": ping.title, onclick: latencyDialog }, AV.pingBadge(ping.ms, ping.text));
  }

  /* Says your screen is on air, how it leaves and how many are watching; your own picture only shows when you open the preview. */
  function shareChip() {
    if (!AV.rtc.localShare) return E("span", { id: "share-chip", class: "hidden" });
    const info = AV.rtc.shareInfo();
    const watching = AV.rtc.viewers().length;
    const mbps = info && info.totalKbps ? (info.totalKbps >= 1000 ? " · " + (info.totalKbps / 1000).toFixed(1) + " Mbps" : " · " + info.totalKbps + " kbps") : "";
    const detail = info ? " · " + info.width + "×" + info.height + " · " + info.fps + " FPS" + mbps : "";
    return E("button", { id: "share-chip", class: "chip text-danger", title: "Sua tela está sendo transmitida. Clique para ver quem assiste.", onclick: () => viewersDialog(me().id) },
      AV.icon("eye", "ic-sm"), String(watching), E("span", { class: "hidden lg:inline" }, "· Transmitindo" + detail));
  }

  function renderPing() {
    if (!AV.state) return;
    const chip = $("ping-chip");
    if (chip) chip.replaceWith(pingChip());
    const share = $("share-chip");
    if (share) share.replaceWith(shareChip());
    document.querySelectorAll("[data-ping-peer]").forEach((node) => AV.fill(node, AV.pingBadge(peerPing(node.dataset.pingPeer))));
  }

  function latencyDialog() {
    const online = AV.state.peers.filter((p) => p.online);
    AV.modal("Qualidade da conexão", (body) => {
      body.append(E("p", { class: "mb-3 text-sm text-muted" }, "O ping é o tempo de ida e volta até cada pessoa. Até 100 ms é bom, até 200 ms é intermediário e acima disso a conversa começa a atrasar."));
      if (!online.length) body.append(E("p", { class: "text-muted" }, "Ninguém conectado para medir a latência."));
      online.forEach((p) => body.append(E("div", { class: "row" },
        E("div", { class: "flex min-w-0 items-center gap-3" }, AV.avatar(p.id, p.name, "avatar-sm", p.online), E("span", { class: "truncate font-semibold" }, p.name)),
        E("span", { dataset: { pingPeer: p.id } }, AV.pingBadge(peerPing(p.id))))));
    }, [{ label: "Fechar", run: (close) => close() }]);
  }

  /* ---- incoming calls ---- */

  function incomingCard(call) {
    const where = call.room.startsWith("dm:") ? "Chamada privada" : "Chamada em " + roomName(call.room);
    return E("div", { class: "incoming", role: "alertdialog", "aria-label": call.callerName + " está chamando você" },
      E("div", { class: "flex min-w-0 items-center gap-4" },
        E("div", { class: "incoming-avatar" }, AV.avatar(call.callerId, call.callerName, "avatar-lg")),
        E("div", { class: "min-w-0 flex-1" },
          E("div", { class: "text-xs font-bold uppercase tracking-wider text-ok" }, "Chamada recebida"),
          E("div", { class: "truncate text-lg font-bold" }, call.callerName),
          E("div", { class: "truncate text-sm text-muted" }, where))),
      E("div", { class: "mt-4 grid grid-cols-2 gap-2" },
        E("button", { class: "btn btn-answer", onclick: () => answerCall(call) }, AV.icon("phone", "ic-sm"), "Atender"),
        E("button", { class: "btn btn-danger", onclick: () => declineCall(call) }, AV.icon("phone-off", "ic-sm"), "Recusar")));
  }

  function syncRingtone() {
    if ((AV.state.incoming || []).length) AV.ringtone.start(AV.rtc.room ? "waiting" : "incoming");
    else if (AV.rtc.calling) AV.ringtone.start("outgoing");
    else AV.ringtone.stop();
  }

  function renderIncoming() {
    const calls = AV.state.incoming || [];
    const key = calls.map((c) => c.room + "|" + c.callerName).join(",");
    if (key !== incomingKey) {
      // Rebuilding the card restarts its pulse, so it only happens when the list of calls really changes.
      incomingKey = key;
      AV.fill($("incoming"), ...calls.map(incomingCard));
      renderBanners();
    }
    syncRingtone();
  }

  function dropIncoming(call) {
    AV.state.incoming = (AV.state.incoming || []).filter((c) => c.room !== call.room);
    renderIncoming();
  }

  async function answerCall(call) {
    dropIncoming(call);
    await startCall(chatOfRoom(call.room));
  }

  function declineCall(call) {
    dropIncoming(call);
    AV.api("POST", "/api/call/decline", { room: call.room }).catch(AV.fail);
  }

  function onCallAction({ action, room }) {
    const call = (AV.state.incoming || []).find((c) => c.room === room);
    if (!call) return;
    if (action === "answer") answerCall(call);
    else declineCall(call);
  }

  function renderBottom() {
    const rtc = AV.rtc;
    const inCall = !!rtc.room;
    const r = AV.state.net;
    const btn = (icon, title, onclick, cls, disabled) => E("button", { class: "btn btn-icon " + (cls || ""), title, "aria-label": title, onclick, disabled: disabled || false }, AV.icon(icon));
    AV.fill($("bottombar"), 
      E("div", { class: "flex min-w-0 items-center gap-3" }, AV.avatar(me().id, me().name, "", true),
        E("div", { class: "hidden min-w-0 sm:block" }, E("div", { class: "truncate font-semibold" }, me().name),
          E("div", { class: "flex items-center gap-2 text-xs text-muted" }, E("span", { class: "dot " + (r.connected ? "dot-on" : "dot-off") }), r.connected ? "Conectado" : "Sem conexão"))),
      E("div", { class: "flex-1" }),
      E("div", { class: "flex items-center gap-2" },
        shareChip(),
        pingChip(),
        btn(rtc.muted ? "mic-off" : "mic", rtc.muted ? "Ativar microfone" : "Silenciar microfone", () => { rtc.toggleMute(); }, rtc.muted ? "btn-off" : ""),
        btn(rtc.deafened ? "headphones-off" : "headphones", rtc.deafened ? "Ativar áudio" : "Silenciar áudio", () => { rtc.toggleDeafen(); }, rtc.deafened ? "btn-off" : ""),
        btn("screen", rtc.localShare ? "Parar compartilhamento" : "Compartilhar tela", shareToggle, rtc.localShare ? "btn-live" : "", !inCall),
        inCall && E("button", { class: "btn btn-danger", onclick: () => rtc.leave() }, AV.icon("phone-off", "ic-sm"), E("span", { class: "hidden sm:inline" }, "Desligar")),
        btn("settings", "Configurações", () => AV.openSettings("audio"))));
  }

  async function shareToggle() {
    try {
      if (AV.rtc.localShare) { AV.rtc.stopShare(); return; }
      const sources = AV.rtc.shareSources();
      const source = sources.length > 1 ? await pickShareSource(sources) : sources[0];
      if (source && AV.rtc.room && !AV.rtc.localShare) await AV.rtc.startShare(source.id);
    } catch (e) {
      if (e && e.name !== "NotAllowedError" && e.name !== "AbortError") AV.toast("Não foi possível compartilhar a tela.", "error");
    }
  }

  function pickShareSource(sources) {
    return new Promise((resolve) => {
      let chosen = null;
      AV.modal("Escolha a tela", (body, done) => {
        body.append(E("div", { class: "grid grid-cols-1 gap-3 sm:grid-cols-2" }, sources.map((source) => {
          const preview = E("div", { class: "share-preview" }, E("span", { class: "text-xs text-muted" }, "Carregando prévia…"));
          AV.rtc.previewSource(source.id)
            .then((url) => AV.fill(preview, E("img", { src: url, alt: "Prévia de " + source.label })))
            .catch(() => AV.fill(preview, AV.icon("screen")));
          return E("button", { class: "share-source", onclick: () => { chosen = source; done(); } },
            preview, E("span", { class: "font-semibold" }, source.label));
        })));
      }, [{ label: "Cancelar", run: (done) => done() }], () => resolve(chosen));
    });
  }

  /* Full-screen video: controls appear on mouse movement and hide after a short pause, so only the picture stays. */
  const HUD_IDLE_MS = 2200;
  let hudTimer = 0;
  function showHud(tile) {
    tile.classList.add("hud");
    clearTimeout(hudTimer);
    hudTimer = setTimeout(() => { if (tile.querySelector(".tile-video-header:hover")) showHud(tile); else tile.classList.remove("hud"); }, HUD_IDLE_MS);
  }
  /* Only a real move counts: Chromium sends a still "mousemove" when the controls fade, which would bring them back forever. */
  let lastPointer = "";
  document.addEventListener("mousemove", (e) => {
    const at = e.screenX + "," + e.screenY;
    if (at === lastPointer) return;
    lastPointer = at;
    const tile = document.querySelector(".tile-video.fullscreen");
    if (tile) showHud(tile);
  }, { passive: true });
  document.addEventListener("mouseleave", () => { const tile = document.querySelector(".tile-video.fullscreen.hud"); if (tile) tile.classList.remove("hud"); });

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

  function buildCallView() {
    const status = E("div", { class: "call-status hidden", role: "status" });
    const stage = E("div", { class: "stage hidden" });
    const people = E("div", { class: "people" });
    return { root: E("div", { class: "call-view" }, status, stage, people), status, stage, people, videos: new Map(), tiles: new Map() };
  }

  function iconButton(onclick) {
    return E("button", { class: "btn btn-ghost btn-icon !h-7 !w-7", onclick: (e) => { e.stopPropagation(); onclick(); } });
  }

  function setIconButton(button, icon, label, on) {
    if (button.dataset.icon !== icon) {
      AV.fill(button, AV.icon(icon, "ic-sm"));
      button.dataset.icon = icon;
    }
    button.title = label;
    button.setAttribute("aria-label", label);
    button.classList.toggle("btn-off", !!on);
  }

  function setVideoStream(view, stream) {
    if (view.video.srcObject === stream) return;
    view.video.srcObject = stream;
    view.root.classList.toggle("waiting", !!stream);
  }

  /* Video nodes are created once and only ever updated: rebuilding them on every state change is what made the picture blink. */
  function videoTile(tile) {
    let view = callView.videos.get(tile.key);
    if (view) {
      setVideoStream(view, tile.stream);
      return view;
    }
    // Every sound goes through Web Audio, where mute and volume apply; audio from this element would play on top of it.
    const video = E("video", { autoplay: true, playsinline: true, muted: true, "aria-label": "Tela de " + nameOf(tile.id) });
    video.muted = true;
    const title = E("div", { class: "tile-name truncate" });
    const info = E("span", { class: "mono flex-none text-muted" });
    const viewers = E("button", { class: "tile-viewers", onclick: (e) => { e.stopPropagation(); viewersDialog(tile.id); } });
    const actions = E("div", { class: "flex flex-none items-center gap-1" });
    const view0 = { video, title, info, viewers, actions };
    if (tile.local) {
      view0.preview = iconButton(() => AV.rtc.toggleSelfPreview());
      view0.stop = iconButton(() => AV.rtc.stopShare());
      actions.append(view0.preview, view0.stop);
    } else {
      view0.sound = iconButton(() => AV.rtc.toggleShareMute(tile.id));
      view0.level = E("span", { class: "mono w-9 text-right text-xs text-muted" });
      view0.volume = E("input", { type: "range", min: 0, max: 100, class: "w-24", value: AV.rtc.shareVolume(tile.id),
        "aria-label": "Volume da transmissão",
        onclick: (e) => e.stopPropagation(), ondblclick: (e) => e.stopPropagation(),
        oninput: (e) => { AV.rtc.setShareVolume(tile.id, Number(e.target.value)); view0.level.textContent = e.target.value + "%"; } });
      // The slider only opens over the speaker icon, on a solid card, so it never sits half-visible over the picture.
      const volumeGroup = E("div", { class: "tile-volume" }, view0.sound,
        E("div", { class: "tile-volume-pop" }, E("div", { class: "tile-volume-card" }, view0.volume, view0.level)));
      view0.close = iconButton(() => AV.rtc.watch(tile.id, false));
      actions.append(volumeGroup, view0.close);
    }
    view0.expand = iconButton(() => toggleFullscreen(tile.key));
    actions.append(view0.expand);
    const header = E("div", { class: "tile-video-header" },
      E("div", { class: "flex min-w-0 items-center gap-2" }, E("span", { class: "live-badge" }, "Ao vivo"), title, info, viewers), actions);
    view0.cover = E("div", { class: "tile-cover" });
    view0.loading = E("div", { class: "tile-loading", role: "status" }, "Carregando transmissão…");
    const root = E("div", { class: "tile tile-video", dataset: { tile: tile.key }, tabindex: 0,
      onclick: () => { focusedTile = tile.key; renderCall(); },
      ondblclick: () => { if (view0.video.srcObject) toggleFullscreen(tile.key); } }, header, video, view0.loading, view0.cover);
    view = { root, ...view0 };
    const shown = () => { if (video.videoWidth) root.classList.remove("waiting"); };
    video.addEventListener("resize", shown);
    video.addEventListener("playing", shown);
    setVideoStream(view, tile.stream);
    callView.videos.set(tile.key, view);
    callView.stage.append(root);
    return view;
  }

  function coverFor(tile) {
    const name = nameOf(tile.id);
    if (tile.local) {
      return [AV.avatar(tile.id, name, "avatar-lg"), E("div", { class: "tile-cover-title font-semibold" }, "Você está transmitindo"),
        E("div", { class: "tile-cover-note text-sm text-muted" }, "A prévia fica desligada para poupar o computador."),
        E("div", { class: "tile-cover-actions" },
          E("button", { class: "btn", onclick: (e) => { e.stopPropagation(); AV.rtc.toggleSelfPreview(); } }, AV.icon("eye", "ic-sm"), "Ver minha transmissão"),
          E("button", { class: "btn btn-danger", onclick: (e) => { e.stopPropagation(); AV.rtc.stopShare(); } }, AV.icon("x", "ic-sm"), "Parar transmissão"))];
    }
    return [AV.avatar(tile.id, name, "avatar-lg"), E("div", { class: "tile-cover-title font-semibold" }, name + " está transmitindo"),
      E("button", { class: "btn btn-primary", onclick: (e) => { e.stopPropagation(); focusedTile = tile.key; AV.rtc.watch(tile.id, true); } }, AV.icon("eye", "ic-sm"), "Assistir transmissão")];
  }

  function viewersDialog(id) {
    const ids = AV.rtc.shareViewers(id);
    const title = id === me().id ? "Quem está assistindo você" : "Assistindo " + nameOf(id);
    AV.modal(title, (body) => {
      if (!ids.length) body.append(E("p", { class: "text-muted" }, "Ninguém está assistindo esta transmissão agora."));
      ids.forEach((v) => body.append(E("div", { class: "flex items-center gap-3 py-2" }, AV.avatar(v, nameOf(v), "avatar-sm"),
        E("span", { class: "truncate font-semibold" }, v === me().id ? "Você" : nameOf(v)))));
    }, [{ label: "Fechar", run: (close) => close() }]);
  }

  function fillViewers(node, id) {
    const ids = AV.rtc.shareViewers(id);
    const key = ids.join(",");
    if (node.dataset.key === key) return;
    node.dataset.key = key;
    const names = ids.map((v) => (v === me().id ? "Você" : nameOf(v)));
    const label = ids.length ? (ids.length === 1 ? "1 pessoa assistindo: " : ids.length + " pessoas assistindo: ") + names.join(", ") : "Ninguém assistindo";
    node.title = label;
    node.setAttribute("aria-label", label);
    AV.fill(node, AV.icon("eye", "ic-sm"), E("span", { class: "mono" }, String(ids.length)),
      E("span", { class: "tile-viewer-faces" }, ids.slice(0, 3).map((v) => AV.avatar(v, nameOf(v), "avatar-xs"))));
  }

  function videoInfo(tile) {
    if (!tile.stream) return "";
    if (tile.local) {
      const info = AV.rtc.shareInfo();
      if (!info) return "";
      const mbps = info.kbps >= 1000 ? (info.kbps / 1000).toFixed(1) + " Mbps" : (info.kbps ? info.kbps + " kbps" : "");
      return info.width + "×" + info.height + " · " + info.fps + " FPS" + (mbps ? " · " + mbps : "") + (info.codec ? " · " + info.codec : "");
    }
    const data = AV.rtc.peerStats(tile.id);
    if (!data || !data.inHeight) return "";
    const mbps = data.inKbps >= 1000 ? (data.inKbps / 1000).toFixed(1) + " Mbps" : (data.inKbps ? data.inKbps + " kbps" : "");
    return data.inWidth + "×" + data.inHeight + " · " + data.inFps + " FPS" + (mbps ? " · " + mbps : "") + (data.codec ? " · " + data.codec : "");
  }

  function syncVideos(tiles) {
    const keys = new Set(tiles.map((t) => t.key));
    callView.videos.forEach((view, key) => {
      if (keys.has(key)) return;
      view.video.srcObject = null;
      view.root.remove();
      callView.videos.delete(key);
      if (activeFullscreenTile === key) {
        activeFullscreenTile = null;
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
      }
    });
    if (!keys.has(focusedTile)) focusedTile = tiles.length ? tiles[0].key : null;
    tiles.forEach((tile) => {
      const view = videoTile(tile);
      if (!tile.stream && activeFullscreenTile === tile.key) {
        activeFullscreenTile = null;
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
      }
      const full = activeFullscreenTile === tile.key;
      const open = !!tile.stream;
      view.root.classList.toggle("focused", tile.key === focusedTile);
      view.root.classList.toggle("fullscreen", full);
      if (full) showHud(view.root);
      view.root.classList.toggle("closed", !open);
      view.title.textContent = tile.local ? "Sua tela" : "Tela de " + nameOf(tile.id);
      view.info.textContent = videoInfo(tile);
      fillViewers(view.viewers, tile.id);
      const coverKey = open ? "" : (tile.local ? "self" : "peer") + "|" + nameOf(tile.id) + "|" + AV.avatarVersion(tile.id);
      if (view.coverKey !== coverKey) { view.coverKey = coverKey; AV.fill(view.cover, open ? [] : coverFor(tile)); }
      view.expand.classList.toggle("hidden", !open);
      if (tile.local) {
        setIconButton(view.preview, AV.rtc.selfPreview ? "eye-off" : "eye", AV.rtc.selfPreview ? "Ocultar minha transmissão" : "Ver minha transmissão", false);
        setIconButton(view.stop, "x", "Parar transmissão", true);
      } else {
        const muted = AV.rtc.isShareMuted(tile.id);
        if (document.activeElement !== view.volume) view.volume.value = AV.rtc.shareVolume(tile.id);
        view.level.textContent = view.volume.value + "%";
        setIconButton(view.sound, muted ? "volume-x" : "volume", muted ? "Reativar som da transmissão" : "Silenciar som da transmissão", muted);
        view.close.classList.toggle("hidden", !open);
        setIconButton(view.close, "eye-off", "Fechar transmissão", false);
      }
      setIconButton(view.expand, full ? "minimize" : "maximize", full ? "Sair da tela cheia (Esc)" : "Abrir em tela cheia", false);
    });
    callView.stage.classList.toggle("hidden", tiles.length === 0);
    callView.stage.classList.toggle("single", tiles.length === 1);
    setStageMode(tiles.length > 0);
  }

  function setStageMode(on) {
    $("call-panel").classList.toggle("call-stage", on);
    // A shared screen needs the height: the conversation steps aside until someone asks for it.
    document.getElementById("shell").classList.toggle("stage-focus", on && !chatOverStage);
    const toggle = $("stage-chat");
    if (toggle) toggle.classList.toggle("hidden", !on);
  }

  function personTile(id) {
    let view = callView.tiles.get(id);
    if (view) return view;
    const avatarSlot = E("div", {});
    const name = E("div", { class: "tile-name max-w-full truncate" });
    const flags = E("div", { class: "tile-flags" });
    const root = E("div", { class: "tile person", dataset: { peer: id } }, avatarSlot, name, flags);
    view = { root, avatarSlot, name, flags, avatarKey: null, flagsKey: null };
    if (id !== me().id) {
      view.mute = E("button", { class: "btn btn-ghost btn-icon !h-8 !w-8", onclick: () => AV.rtc.togglePeerMute(id) });
      view.slider = E("input", { type: "range", min: 0, max: 200, class: "w-24", "aria-label": "Volume de " + nameOf(id),
        title: "Volume de " + nameOf(id), oninput: (e) => AV.rtc.setPeerVolume(id, Number(e.target.value)) });
      view.ping = E("span", { dataset: { pingPeer: id } }, AV.pingBadge(peerPing(id)));
      root.append(view.ping, E("div", { class: "flex items-center gap-1" }, view.mute, view.slider));
    }
    callView.tiles.set(id, view);
    callView.people.append(root);
    return view;
  }

  function flagIcon(icon, label) {
    return E("span", { title: label, "aria-label": label, role: "img" }, AV.icon(icon, "ic-sm text-danger"));
  }

  function personFlags(id, self) {
    const rtc = AV.rtc;
    if (self) {
      return [rtc.localShare && E("span", { class: "live-badge" }, "Ao vivo"), rtc.muted && flagIcon("mic-off", "Seu microfone está silenciado"), rtc.deafened && flagIcon("headphones-off", "Seu áudio está silenciado")];
    }
    const state = rtc.peerState(id);
    const link = rtc.connectionState(id);
    return [
      state && state.muted && flagIcon("mic-off", "Microfone desligado"),
      state && state.deafened && flagIcon("headphones-off", "Áudio desligado"),
      rtc.isSharing(id) && E("span", { class: "live-badge" }, "Ao vivo"),
      rtc.isPeerMuted(id) && E("span", { class: "chip !h-5 !px-2 text-danger" }, "Silenciado por você"),
      link !== "connected" && E("span", { class: "chip !h-5 !px-2" }, link === "failed" || link === "disconnected" ? "Reconectando…" : "Conectando…"),
    ];
  }

  function updatePerson(id) {
    const rtc = AV.rtc;
    const self = id === me().id;
    const view = personTile(id);
    const label = nameOf(id);
    const avatarKey = label + "|" + AV.avatarVersion(id);
    if (view.avatarKey !== avatarKey) {
      AV.fill(view.avatarSlot, AV.avatar(id, label, "avatar-lg"));
      view.avatarKey = avatarKey;
    }
    view.name.textContent = self ? "Você" : label;
    view.root.classList.toggle("speaking", !!rtc.speaking[id]);
    AV.fill(view.flags, ...personFlags(id, self).filter(Boolean));
    if (!self) {
      const muted = rtc.isPeerMuted(id);
      view.root.classList.toggle("muted-local", muted);
      setIconButton(view.mute, muted ? "volume-x" : "volume", muted ? "Reativar o áudio de " + label : "Silenciar " + label + " só para você", muted);
      view.mute.setAttribute("aria-pressed", String(muted));
      view.slider.disabled = muted;
      if (document.activeElement !== view.slider) view.slider.value = rtc.peerVolume(id);
    }
  }

  function syncPeople(ids) {
    const wanted = new Set(ids);
    callView.tiles.forEach((view, id) => {
      if (!wanted.has(id)) { view.root.remove(); callView.tiles.delete(id); }
    });
    ids.forEach(updatePerson);
  }

  function renderCallStatus() {
    const calling = AV.rtc.calling;
    callView.status.classList.toggle("hidden", !calling);
    if (calling) {
      const peer = isDm(AV.chat) ? peerOf(AV.chat.slice(3)) : null;
      AV.fill(callView.status, AV.icon("phone", "ic-sm"), peer ? "Chamando " + peer.name + "…" : "Chamando os participantes…");
    }
  }

  function renderCall() {
    const panel = $("call-panel");
    const room = roomOf(AV.chat);
    const active = AV.state.calls[room] || [];
    const rtc = AV.rtc;
    const inThis = rtc.room === room;
    if (!inThis) {
      callView = null;
      setStageMode(false);
      if (active.length === 0 && !rtc.room) { panel.classList.add("hidden"); AV.fill(panel); return; }
      panel.classList.remove("hidden");
      const here = rtc.room ? "Você está em outra chamada." : active.map(nameOf).join(", ") + (active.length > 1 ? " estão" : " está") + " em chamada.";
      AV.fill(panel, E("div", { class: "flex items-center gap-3" }, AV.icon("volume", "text-ok"), E("div", { class: "flex-1" }, here),
        !rtc.room && E("button", { class: "btn btn-primary", onclick: () => startCall(AV.chat) }, "Entrar na chamada"),
        rtc.room && E("button", { class: "btn", onclick: () => selectChat(chatOfRoom(rtc.room)) }, "Ir para a chamada")));
      return;
    }
    panel.classList.remove("hidden");
    if (!callView) {
      callView = buildCallView();
      AV.fill(panel, callView.root);
    }
    renderCallStatus();
    syncVideos(rtc.videoTiles());
    syncPeople([...new Set([me().id, ...active])]);
  }

  async function startCall(chat) {
    try {
      await AV.rtc.join(roomOf(chat));
      if (AV.chat !== chat) selectChat(chat);
    } catch (e) {
      AV.fail(e);
    }
  }

  AV.rtc.onchange = () => { renderHeader(); renderCall(); renderBottom(); renderNav(); renderMembers(); syncRingtone(); };
  AV.rtc.onstats = () => { renderCall(); renderPing(); };
  AV.rtc.onspeaking = (id, value) => {
    document.querySelectorAll('[data-peer="' + id + '"]').forEach((n) => n.classList.toggle("speaking", value));
    pushOverlay();
  };

  let lastOverlay = "";
  const pushOverlay = AV.debounce(() => {
    const speaking = AV.rtc.room ? Object.entries(AV.rtc.speaking).filter(([id, on]) => on && AV.rtc.isAudible(id)).map(([id]) => ({ id, name: nameOf(id) })) : [];
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

  async function removePeer(peer) {
    if (!(await AV.confirm("Remover " + peer.name + " da lista?", "A conversa continua no servidor e volta para a lista quando essa pessoa escrever de novo.", "Remover", true))) return;
    try {
      await AV.api("DELETE", "/api/peers/" + peer.id);
      AV.chat = "publico";
      await AV.refresh();
      selectChat("publico");
    } catch (e) { AV.fail(e); }
  }

  async function deleteGroup(group) {
    if (!(await AV.confirm("Sair de " + group.name + "?", "Você deixa de receber as mensagens dele. Para voltar, use um convite.", "Sair do grupo", true))) return;
    try {
      await AV.api("DELETE", "/api/groups/" + group.id);
      AV.chat = "publico";
      await AV.refresh();
      selectChat("publico");
      AV.toast("Você saiu do grupo.", "ok");
    } catch (e) { AV.fail(e); }
  }

  /* ---- messages: the conversation pane lives in chat.js ---- */

  function selectChat(chat) {
    AV.chat = chat;
    renderAll();
    AV.conv.select(chat);
    AV.api("PUT", "/api/settings", { "ui.lastChat": chat }).catch(() => {});
    $("composer").focus();
  }

  async function openLink(url) {
    if (await AV.confirm("Abrir este link?", url, "Abrir no navegador")) AV.api("POST", "/api/system/open", { url }).catch(AV.fail);
  }

  /* Administrator actions go to the server, which decides; the page only says what happened. */
  async function admin(action, body, done) {
    try {
      const r = await AV.api("POST", "/api/admin/" + action, body || {});
      if (done) AV.toast(done, "ok");
      return r;
    } catch (e) { AV.fail(e); return null; }
  }

  function banDialog(id) {
    const reason = E("input", { class: "input", maxlength: 200, placeholder: "Motivo (opcional)", "aria-label": "Motivo do banimento" });
    AV.modal("Banir " + nameOf(id) + "?", (body) => body.append(
      E("p", { class: "mb-3 text-sm text-muted" }, "A pessoa é desconectada e não consegue voltar, nem por esta identidade nem pelo endereço de internet de onde conectou."), reason), [
      { label: "Cancelar", run: (close) => close() },
      { label: "Banir", danger: true, run: async (close) => { close(); await admin("ban", { id, reason: reason.value.trim() }, nameOf(id) + " foi banido."); } }]);
  }

  /* Ctrl+K: jump to any conversation by typing part of its name. */
  function quickSwitcher() {
    const targets = [...AV.state.groups.map((g) => ({ id: g.id, name: g.name, icon: () => AV.icon(g.kind === "public" ? "network" : "users", "ic-sm") })),
      ...AV.state.peers.map((p) => ({ id: "dm:" + p.id, name: p.name, icon: () => AV.avatar(p.id, p.name, "avatar-xs", p.online) }))];
    let index = 0;
    let close = null;
    const list = E("div", { class: "mt-3 flex max-h-80 flex-col gap-1 overflow-y-auto", role: "listbox" });
    const input = E("input", { class: "input", placeholder: "Para onde você quer ir?", "aria-label": "Buscar conversa" });
    const matches = () => targets.filter((t) => AV.emoji.fold(t.name).includes(AV.emoji.fold(input.value.trim())));
    const go = (t) => { close(); selectChat(t.id); };
    const draw = () => {
      const found = matches();
      index = Math.min(index, Math.max(0, found.length - 1));
      AV.fill(list, found.length ? found.map((t, i) => E("button", { class: "nav-item", role: "option", "aria-current": String(i === index), onclick: () => go(t) }, t.icon(), E("span", { class: "truncate" }, t.name)))
        : E("div", { class: "px-2 py-3 text-sm text-muted" }, "Nenhuma conversa com esse nome."));
    };
    input.addEventListener("input", () => { index = 0; draw(); });
    input.addEventListener("keydown", (e) => {
      const found = matches();
      if (e.key === "ArrowDown") { e.preventDefault(); index = Math.min(index + 1, found.length - 1); draw(); }
      if (e.key === "ArrowUp") { e.preventDefault(); index = Math.max(index - 1, 0); draw(); }
      if (e.key === "Enter" && found[index]) { e.preventDefault(); go(found[index]); }
    });
    close = AV.modal("Ir para uma conversa", (body) => { body.append(input, list); draw(); }, []);
    input.focus();
  }

  AV.renderNav = () => renderNav();
  AV.renderHeaderSoon = () => renderHeader();
  AV.openLink = openLink;
  AV.selectChat = selectChat;
  AV.admin = admin;
  AV.banDialog = banDialog;

  /* ---- live events ---- */

  function onHotkey({ action, state }) {
    const rtc = AV.rtc;
    if (action === "ptt") { rtc.setPtt(state === "down"); return; }
    if (state !== "down") return;
    if (action === "mic") { rtc.toggleMute(); AV.toast(rtc.muted ? "Microfone silenciado." : "Microfone ativado."); }
    if (action === "deafen") { rtc.toggleDeafen(); AV.toast(rtc.deafened ? "Áudio silenciado." : "Áudio ativado."); }
    if (action === "muteAll") { rtc.toggleMuteAll(); AV.toast(rtc.muteAll ? "Todos silenciados." : "Todos reativados."); }
    if (action === "call") {
      const ringing = (AV.state.incoming || [])[0];
      if (ringing) answerCall(ringing);
      else if (rtc.room) rtc.leave();
      else startCall(AV.chat);
    }
    if (action === "share") {
      if (!rtc.room) { AV.toast("Entre em uma chamada para compartilhar a tela."); return; }
      shareToggle();
    }
  }

  /* Every update is mandatory and Java drives it. This only leaves the call on time and says what is happening; there is nothing to choose. */
  function enforceUpdateStatus(u) {
    if (!u) return;
    const updating = u.available && u.available !== u.current && ["available", "downloading", "validating", "ready", "installing", "restarting"].includes(u.state);
    let lock = document.getElementById("update-lock");
    if (!updating) {
      if (lock) lock.remove();
      return;
    }
    if (AV.rtc.room) AV.rtc.leave();
    if (AV.ringtone) AV.ringtone.stop();
    if (!lock) {
      lock = E("div", { id: "update-lock", class: "overlay update-lock", role: "status" });
      document.body.append(lock);
    }
    const progress = Math.round((u.progress || 0) * 100);
    const bar = E("i");
    bar.style.width = progress + "%";
    AV.fill(lock, E("div", { class: "card max-w-md p-6 text-center" },
      E("div", { class: "text-lg font-bold" }, "Atualizando para a versão " + u.available),
      E("p", { class: "mt-2 text-sm text-muted" }, "Todos precisam estar na mesma versão. O Angatu Voice reinicia sozinho em instantes."),
      u.state === "downloading" && E("div", { class: "mt-4" }, E("div", { class: "progress" }, bar), E("small", { class: "text-muted" }, progress + "%"))));
  }

  function onUpdate(update) {
    const sameState = lastUpdateState === update.state;
    lastUpdateState = update.state;
    AV.state.update = update;
    renderBanners();
    enforceUpdateStatus(update);
    if (sameState && update.state === "downloading") {
      const bar = document.querySelector(".progress > i");
      if (bar) bar.style.width = Math.round(update.progress * 100) + "%";
    } else {
      AV.refreshSettings();
    }
  }

  function wire() {
    AV.on("open", () => { linkDown = false; AV.refresh().then(() => AV.conv.render()).catch(AV.fail); });
    AV.on("link", (up) => { if (!up) { linkDown = true; renderBanners(); } });
    AV.on("expired", () => AV.modal("Sessão encerrada", (b) => b.append(E("p", { class: "text-muted" }, "Feche e abra o Angatu Voice de novo para continuar.")), []));
    AV.on("peer.online", ({ id, name }) => { AV.toast(name + " entrou na rede."); AV.rtc.resync(id); refreshSoon(); });
    AV.on("peer.offline", ({ name }) => { AV.toast(name + " saiu da rede."); refreshSoon(); });
    AV.on("peer.changed", () => refreshSoon());
    AV.on("peer.relinked", ({ id }) => { AV.rtc.resync(id); refreshSoon(); });
    AV.on("net.latency", ({ peers }) => { AV.latency = { peers, at: Date.now() }; renderPing(); });
    AV.on("net", () => refreshSoon());
    AV.on("group.changed", () => refreshSoon());
    AV.on("peer.profile", () => refreshSoon());
    AV.on("admin", (a) => { AV.state.me.admin = a; renderHeader(); AV.refreshSettings(); });
    AV.on("net.refused", ({ reason }) => AV.modal(reason === "banned" ? "Você foi banido" : "Você foi desconectado",
      (b) => b.append(E("p", { class: "text-muted" }, reason === "banned" ? "Um administrador baniu você do servidor do Angatu Voice." : "Um administrador desconectou você. O aplicativo tenta voltar em um minuto.")),
      [{ label: "Entendi", primary: true, run: (close) => close() }]));
    AV.on("chat.new", (m) => { if (m.chat.startsWith("dm:") && !peerOf(m.chat.slice(3))) refreshSoon(); });
    AV.on("peer.removed", ({ id }) => { if (AV.chat === "dm:" + id) { selectChat("publico"); } refreshSoon(); });
    AV.on("call.state", (d) => { AV.rtc.handleRoom(d); renderHeader(); renderCall(); renderNav(); renderMembers(); renderBanners(); });
    AV.on("call.incoming", ({ calls }) => { AV.state.incoming = calls; renderIncoming(); });
    AV.on("call.action", onCallAction);
    AV.on("call.declined", (d) => AV.rtc.onDeclined(d));
    AV.on("call.missed", ({ name }) => { AV.sound("missed"); AV.toast("Chamada perdida de " + name + "."); });
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
    /* Leaving full screen (Esc, or the button) must leave the conversation exactly where it was: only the header icon changes. */
    document.addEventListener("fullscreenchange", () => {
      if (!document.fullscreenElement && activeFullscreenTile) {
        activeFullscreenTile = null;
        renderCall();
      }
      document.documentElement.classList.toggle("is-fullscreen", !!document.fullscreenElement);
      renderHeader();
    });
    document.addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k" && !document.querySelector(".overlay") && !AV.settingsOpen()) { e.preventDefault(); quickSwitcher(); }
    });
    $("btn-open-settings").addEventListener("click", () => AV.openSettings("app"));
    $("brand-link").addEventListener("click", AV.openBrandSite);
    AV.conv.wire();
    setInterval(renderPing, 3000);
  }

  async function init() {
    try {
      await AV.refresh();
      if (AV.state && AV.state.version) {
        document.title = "Angatu Voice v" + AV.state.version;
      }
      wire();
      lastUpdateState = AV.state.update.state;
      enforceUpdateStatus(AV.state.update);
      const last = setting("ui.lastChat");
      const valid = groupOf(last) || (isDm(last) && peerOf(last.slice(3)));
      await selectChat(valid ? last : "publico");
      AV.live.start();
      document.getElementById("boot").classList.add("done");
    } catch (e) {
      document.getElementById("boot-text").textContent = "Não foi possível carregar o aplicativo. Feche e abra o Angatu Voice de novo.";
      document.querySelector(".boot-bar").remove();
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
