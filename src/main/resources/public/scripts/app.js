"use strict";
/* The application: reads state from the local server, renders it, and reacts to live events. */
(function () {
  const E = AV.el;
  const PAGE = 50;
  const EMOJIS = ["😀", "😁", "😂", "🤣", "😊", "😍", "😎", "🤔", "😅", "😉", "😢", "😭", "😡", "🥳", "😴", "🙄", "👍", "👎", "👏", "🙏", "💪", "👀", "🔥", "✨", "🎉", "🎮", "🎧", "☕", "🍕", "❤️", "💔", "💯", "✅", "❌", "⚠️", "🚀", "💡", "📌", "🎵", "👋"];
  const LATENCY_STALE_MS = 8000;
  const CALL_RTT_STALE_MS = 6000;
  let callView = null;
  let focusedTile = null;
  let chatOverStage = false;
  let incomingKey = "";
  let messages = [];
  let hasMore = false;
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

  function navItem(id, label, iconOrAvatar, unread, extra) {
    return E("button", { class: "nav-item", "aria-current": String(AV.chat === id), title: label, onclick: () => selectChat(id) },
      iconOrAvatar, E("span", { class: "hidden min-w-0 flex-1 truncate md:inline" }, label),
      unread > 0 && E("span", { class: "badge" }, unread > 99 ? "99+" : unread), extra);
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
      group && group.kind === "public" && E("span", { class: "chip ml-3 align-middle" }, E("span", { class: "dot " + (AV.state.net.connected ? "dot-on" : "dot-off") }), AV.state.net.connected ? "Conectado" : "Sem conexão"),
      dm && peer && E("span", { class: "chip ml-3 align-middle" }, peer.online ? "Online" : "Offline"));
    AV.fill($("chat-tools"), 
      group && group.hasInvite && E("button", { class: "btn", onclick: () => inviteDialog(group) }, AV.icon("copy", "ic-sm"), E("span", { class: "hidden lg:inline" }, "Convidar")),
      group && group.kind !== "public" && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Excluir grupo", "aria-label": "Excluir grupo", onclick: () => deleteGroup(group) }, AV.icon("trash")),
      dm && peer && E("button", { class: "btn btn-ghost btn-icon text-muted hover:text-danger", title: "Limpar ou excluir conversa", "aria-label": "Limpar ou excluir conversa", onclick: () => dmOptionsDialog(peer) }, AV.icon("trash")),
      E("button", { id: "stage-chat", class: "btn hidden", onclick: toggleChatOverStage }, AV.icon("chat", "ic-sm"), chatOverStage ? "Ocultar conversa" : "Mostrar conversa"),
      E("button", { class: "btn btn-ghost btn-icon xl:hidden", title: "Participantes", "aria-label": "Participantes", onclick: membersDialog }, AV.icon("users")),
      E("button", { class: "btn " + (inThisCall ? "btn-danger" : "btn-primary"), disabled: dm && peer && !peer.online && !inThisCall,
        onclick: () => (inThisCall ? AV.rtc.leave() : startCall(AV.chat)) },
      AV.icon(inThisCall ? "phone-off" : "phone", "ic-sm"), inThisCall ? "Sair da chamada" : "Chamar"));
    $("composer").placeholder = "Mensagem para " + (dm ? "" : "#") + chatTitle();
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
      banners.push(E("div", { class: "banner error" }, AV.icon("alert"), E("div", { class: "flex-1" }, E("b", {}, "Sem conexão com o servidor. "), "Tentando de novo. Enquanto isso, só as conversas salvas ficam disponíveis."),
        E("button", { class: "btn", onclick: () => AV.openSettings("network") }, "Ver rede")));
    }
    if (linkDown) banners.push(E("div", { class: "banner" }, AV.icon("refresh"), "Reconectando ao aplicativo…"));
    callsElsewhere().forEach(([room, members]) => banners.push(E("div", { class: "banner info" }, AV.icon("phone"),
      E("div", { class: "min-w-0 flex-1 truncate" }, members.map(nameOf).join(", ") + (members.length > 1 ? " estão" : " está") + " em chamada em " + roomName(room) + "."),
      E("button", { class: "btn", onclick: () => selectChat(chatOfRoom(room)) }, "Ver"),
      E("button", { class: "btn btn-primary", onclick: () => startCall(chatOfRoom(room)) }, "Entrar"))));
    const u = AV.state.update;
    if (u.state === "available") banners.push(E("div", { class: "banner info" }, AV.icon("download"), E("div", { class: "flex-1" }, "A versão " + u.available + " está disponível."), E("button", { class: "btn", onclick: () => AV.openSettings("update") }, "Ver detalhes")));
    if (u.state === "ready") banners.push(E("div", { class: "banner info" }, AV.icon("download"), E("div", { class: "flex-1" }, "A versão " + u.available + " está pronta."),
      E("button", { class: "btn btn-primary", onclick: () => AV.api("POST", "/api/update/install").catch(AV.fail) }, "Reiniciar para atualizar")));
    if (u.state === "downloading") banners.push(E("div", { class: "banner info" }, AV.icon("download"), "Baixando a versão " + u.available + " — " + Math.round(u.progress * 100) + "%"));
    if (u.state === "restarting" || u.state === "installing") banners.push(E("div", { class: "banner info" }, AV.icon("refresh"), "Atualizando o Angatu Voice. O aplicativo vai reiniciar sozinho."));
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
                  AV.chat = "publico";
                  await AV.refresh();
                  selectChat("publico");
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
      AV.chat = "publico";
      await AV.refresh();
      selectChat("publico");
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

  function enforceUpdateStatus(u) {
    if (!u) return;
    const isOutdated = u.available && u.available !== u.current &&
      (u.state === "available" || u.state === "downloading" || u.state === "ready" || u.state === "installing" || u.state === "restarting");

    let blocker = document.getElementById("outdated-blocker");
    if (!isOutdated) {
      if (blocker) blocker.remove();
      return;
    }

    // Drop from any ongoing call immediately
    if (rtc && rtc.room) {
      rtc.leave();
    }
    if (AV.ringtone) AV.ringtone.stop();

    if (!blocker) {
      blocker = document.createElement("div");
      blocker.id = "outdated-blocker";
      blocker.className = "fixed inset-0 z-[999999] bg-[#0f0e0d]/95 backdrop-blur-md flex flex-col items-center justify-center p-6 text-center select-none";
      document.body.appendChild(blocker);
    }

    const isReady = u.state === "ready" || u.canInstall;
    const isDownloading = u.state === "downloading";
    const progress = Math.round((u.progress || 0) * 100);

    blocker.innerHTML = `
      <div class="max-w-md w-full bg-[#181614] border border-[#2e2a25] rounded-2xl p-8 shadow-2xl flex flex-col items-center">
        <div class="w-16 h-16 rounded-full bg-amber-500/10 text-amber-500 flex items-center justify-center mb-4">
          <svg class="w-8 h-8 animate-pulse" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
        </div>
        <h2 class="text-xl font-bold text-[#ece7e0] mb-2">Atualização Obrigatória</h2>
        <p class="text-sm text-[#a29a90] mb-6">
          Sua versão do Angatu Voice está desatualizada (<strong>v${u.current}</strong>). Para garantir a compatibilidade e a comunicação com todos os participantes, a atualização para a <strong>v${u.available}</strong> é obrigatória.
        </p>
        ${isDownloading ? `
          <div class="w-full mb-4">
            <div class="flex justify-between text-xs text-[#a29a90] mb-1">
              <span>Baixando atualização…</span>
              <span>${progress}%</span>
            </div>
            <div class="w-full bg-[#26231f] rounded-full h-2 overflow-hidden">
              <div class="bg-amber-500 h-2 rounded-full transition-all duration-300" style="width: ${progress}%"></div>
            </div>
          </div>
        ` : ''}
        ${isReady ? `
          <p class="text-xs text-emerald-400 font-semibold mb-4">Atualização pronta para instalação!</p>
          <button id="btn-force-restart" class="w-full py-3 px-4 rounded-xl font-semibold bg-amber-500 hover:bg-amber-400 text-black shadow-lg transition cursor-pointer">
            Reiniciar agora para atualizar
          </button>
        ` : `
          <div class="flex items-center gap-2 text-xs text-[#a29a90]">
            <svg class="w-4 h-4 animate-spin text-amber-500" viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="12" cy="12" r="10" stroke-width="4" stroke="currentColor" stroke-opacity="0.2"></circle><path d="M12 2a10 10 0 0 1 10 10" stroke-width="4"></path></svg>
            <span>Atualizando automaticamente em segundo plano…</span>
          </div>
        `}
      </div>
    `;

    const restartBtn = blocker.querySelector("#btn-force-restart");
    if (restartBtn) {
      restartBtn.onclick = () => {
        restartBtn.disabled = true;
        restartBtn.innerText = "Reiniciando…";
        AV.api("POST", "/api/update/install").catch(AV.fail);
      };
    }

    if (isReady && !blocker.dataset.autoRestartTriggered) {
      blocker.dataset.autoRestartTriggered = "true";
      setTimeout(() => {
        AV.api("POST", "/api/update/install").catch(() => {});
      }, 2500);
    }
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
    AV.on("open", () => { linkDown = false; AV.refresh().then(loadMessages).catch(AV.fail); });
    AV.on("link", (up) => { if (!up) { linkDown = true; renderBanners(); } });
    AV.on("expired", () => AV.modal("Sessão encerrada", (b) => b.append(E("p", { class: "text-muted" }, "Feche e abra o Angatu Voice de novo para continuar.")), []));
    AV.on("message", onMessage);
    AV.on("message.delivered", ({ id }) => { const m = messages.find((x) => x.id === id); if (m) { m.pending = false; renderMessages(false); } });
    AV.on("peer.online", ({ id, name }) => { AV.toast(name + " entrou na rede."); AV.rtc.resync(id); refreshSoon(); });
    AV.on("peer.offline", ({ name }) => { AV.toast(name + " saiu da rede."); refreshSoon(); });
    AV.on("peer.changed", () => refreshSoon());
    AV.on("peer.relinked", ({ id }) => { AV.rtc.resync(id); refreshSoon(); });
    AV.on("net.latency", ({ peers }) => { AV.latency = { peers, at: Date.now() }; renderPing(); });
    AV.on("net", () => refreshSoon());
    AV.on("group.changed", () => refreshSoon());
    AV.on("peer.profile", () => refreshSoon());
    AV.on("messages.cleared", ({ chat }) => { if (chat === AV.chat) { messages = []; renderMessages(true); } });
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
    document.addEventListener("fullscreenchange", () => {
      if (!document.fullscreenElement && activeFullscreenTile) {
        activeFullscreenTile = null;
        renderCall();
      }
    });
    $("btn-open-settings").addEventListener("click", () => AV.openSettings("app"));
    $("brand-link").addEventListener("click", AV.openBrandSite);
    $("btn-send").addEventListener("click", send);
    $("btn-emoji").addEventListener("click", emojiPicker);
    $("composer").addEventListener("input", autosize);
    $("composer").addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
    });
    window.addEventListener("focus", markRead);
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
