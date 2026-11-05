"use strict";
/* The settings panel: every control maps one-to-one to a key of the server's allow-list. */
(function () {
  const E = AV.el;
  const UPDATE_TEXT = {
    idle: "Aguardando a primeira verificação.",
    checking: "Verificando atualizações…",
    current: "O Angatu Voice está atualizado.",
    available: "Há uma nova versão disponível.",
    downloading: "Baixando a atualização…",
    validating: "Validando o pacote…",
    ready: "Atualização pronta para instalar.",
    installing: "Preparando a instalação…",
    restarting: "Reiniciando o aplicativo…",
    updated: "Atualização concluída.",
    failed: "Falha na atualização.",
    rolledback: "A versão anterior foi restaurada.",
    "installer-required": "Esta versão exige um novo instalador.",
  };
  const KEY_TOKENS = {
    Space: "SPACE", Enter: "ENTER", Tab: "TAB", Backquote: "BACKQUOTE", Minus: "MINUS", Equal: "EQUALS", ArrowUp: "UP",
    ArrowDown: "DOWN", ArrowLeft: "LEFT", ArrowRight: "RIGHT", Home: "HOME", End: "END", PageUp: "PAGE_UP",
    PageDown: "PAGE_DOWN", Insert: "INSERT", Delete: "DELETE", CapsLock: "CAPS_LOCK", Backslash: "BACK_SLASH",
    Semicolon: "SEMICOLON", Quote: "QUOTE", Comma: "COMMA", Period: "PERIOD", Slash: "SLASH",
    BracketLeft: "OPEN_BRACKET", BracketRight: "CLOSE_BRACKET",
  };
  let panel = null;
  let current = "app";
  let stopMicTest = null;

  const value = (key) => AV.state.settings[key];

  async function save(key, next) {
    const before = value(key);
    AV.state.settings[key] = next;
    try {
      await AV.api("PUT", "/api/settings", { [key]: next });
      AV.applySettings(key);
    } catch (e) {
      AV.state.settings[key] = before;
      AV.fail(e);
      render();
    }
  }

  function row(title, desc, control) {
    return E("div", { class: "row" }, E("div", { class: "row-text min-w-0" }, E("div", { class: "font-semibold" }, title), desc && E("small", {}, desc)), control);
  }

  function toggle(key, title, desc, disabled) {
    const input = E("input", { type: "checkbox", "aria-label": title, disabled: disabled || false, onchange: (e) => save(key, String(e.target.checked)) });
    input.checked = value(key) === "true";
    return row(title, desc, E("label", { class: "switch" }, input, E("span")));
  }

  function select(key, title, desc, options) {
    const node = E("select", { class: "input w-56", "aria-label": title, onchange: (e) => save(key, e.target.value) },
      options.map(([v, label]) => E("option", { value: v, selected: v === value(key) }, label)));
    return row(title, desc, node);
  }

  function range(key, title, desc, min, max, unit) {
    const out = E("span", { class: "mono w-14 text-right" }, value(key) + unit);
    const input = E("input", { type: "range", min, max, step: 1, class: "w-44", "aria-label": title,
      oninput: (e) => { out.textContent = e.target.value + unit; },
      onchange: (e) => save(key, e.target.value) });
    input.value = value(key);
    return row(title, desc, E("div", { class: "flex items-center gap-3" }, input, out));
  }

  function hotkey(key, title, desc) {
    const button = E("button", { class: "btn w-44 mono", "aria-label": title });
    const show = () => { button.textContent = value(key) || "Não definido"; };
    show();
    button.addEventListener("click", () => {
      button.textContent = "Pressione as teclas…";
      const onKey = (e) => {
        e.preventDefault();
        if (["Control", "Shift", "Alt", "Meta"].includes(e.key)) return;
        document.removeEventListener("keydown", onKey, true);
        if (e.key === "Escape") { show(); return; }
        if (e.key === "Backspace") { save(key, "").then(show); return; }
        const token = tokenOf(e.code);
        if (!token) { AV.toast("Essa tecla não pode ser usada como atalho.", "error"); show(); return; }
        const mods = [e.ctrlKey && "Ctrl", e.altKey && "Alt", e.shiftKey && "Shift", e.metaKey && "Meta"].filter(Boolean);
        const combo = [...mods, token].join("+");
        const clash = HOTKEYS.find((a) => "hotkeys." + a !== key && value("hotkeys." + a) === combo);
        if (clash) { AV.toast("Essas teclas já são usadas por outro atalho.", "error"); show(); return; }
        save(key, [...mods, token].join("+")).then(show);
      };
      document.addEventListener("keydown", onKey, true);
    });
    return row(title, desc, button);
  }

  function tokenOf(code) {
    if (/^Key[A-Z]$/.test(code)) return code.slice(3);
    if (/^Digit\d$/.test(code)) return code.slice(5);
    if (/^F([1-9]|1\d|2[0-4])$/.test(code)) return code;
    return KEY_TOKENS[code] || null;
  }

  async function devices() {
    let list = await navigator.mediaDevices.enumerateDevices();
    if (list.some((d) => d.kind === "audioinput" && !d.label)) {
      try { (await AV.capture({ audio: true })).getTracks().forEach((t) => t.stop()); } catch (e) { return list; }
      list = await navigator.mediaDevices.enumerateDevices();
    }
    return list;
  }

  async function audioTab(body) {
    const list = await devices();
    const opts = (kind) => [["", "Padrão do sistema"], ...list.filter((d) => d.kind === kind && d.deviceId !== "default" && d.deviceId !== "communications")
      .map((d) => [d.deviceId, d.label || "Dispositivo sem nome"])];
    body.append(
      select("audio.input", "Microfone", "O dispositivo usado nas chamadas.", opts("audioinput")),
      select("audio.output", "Saída de áudio", "Onde você ouve as pessoas.", opts("audiooutput")),
      range("audio.inputVolume", "Volume de entrada", "Ganho aplicado ao seu microfone.", 0, 200, "%"),
      range("audio.outputVolume", "Volume de saída", "Volume geral da chamada.", 0, 200, "%"),
      range("audio.sensitivity", "Sensibilidade do microfone", "Quanto mais alta, mais fácil é abrir o microfone. Em 100%, ele fica sempre aberto.", 0, 100, "%"),
      toggle("audio.ptt", "Apertar para falar", "Só transmite enquanto o atalho estiver pressionado."),
      E("p", { class: "py-3 text-sm text-muted" }, "As teclas de apertar para falar ficam na aba Atalhos."),
      toggle("audio.echo", "Cancelamento de eco", "Evita que sua voz volte pelas caixas de som."),
      toggle("audio.noise", "Supressão de ruído", "Reduz ruídos constantes, como ventilador e teclado."),
      toggle("sounds.enabled", "Sons do aplicativo", "Sons curtos ao silenciar, entrar e sair de chamadas e receber mensagens."),
      toggle("sounds.ring", "Toque de chamada", "Toca a música de chamada enquanto alguém chama você, e um som de espera enquanto você aguarda ser atendido."),
      range("sounds.volume", "Volume dos sons", "Vale também para o toque de chamada.", 0, 100, "%"),
      row("Testar o toque", "Toca alguns segundos da música, com o volume escolhido.", E("button", { class: "btn", onclick: () => AV.ringtone.preview() }, "Tocar")),
      micTest());
  }

  function micTest() {
    const meter = E("i");
    const status = E("small", { class: "text-muted" }, "Fale para ver o nível do microfone.");
    const start = E("button", { class: "btn" }, "Testar microfone");
    const chime = E("button", { class: "btn" }, "Testar áudio");
    let running = null;
    let loopback = false;
    const loopbackToggle = E("input", {
      type: "checkbox",
      "aria-label": "Escutar o próprio áudio",
      onchange: (e) => {
        loopback = e.target.checked;
        if (running && running.monitorGain) {
          running.monitorGain.gain.value = loopback ? 1 : 0;
        }
      }
    });
    const loopbackOption = E("label", { class: "flex items-center gap-2 cursor-pointer text-sm" },
      loopbackToggle,
      E("span", { class: "font-semibold" }, "Escutar o próprio áudio"),
      E("span", { class: "text-xs text-muted" }, "(ideal usar fones de ouvido para evitar microfonia)")
    );
    const stop = () => {
      if (!running) return;
      running.stream.getTracks().forEach((t) => t.stop());
      running.ctx.close();
      cancelAnimationFrame(running.frame);
      running = null;
      meter.style.width = "0";
      start.textContent = "Testar microfone";
    };
    stopMicTest = stop;
    start.addEventListener("click", async () => {
      if (running) { stop(); return; }
      try {
        const device = value("audio.input");
        const stream = await AV.capture({ audio: { deviceId: device ? { exact: device } : undefined,
          echoCancellation: value("audio.echo") === "true", noiseSuppression: value("audio.noise") === "true" } });
        const ctx = new AudioContext();
        const analyser = ctx.createAnalyser();
        const gain = ctx.createGain();
        gain.gain.value = Number(value("audio.inputVolume")) / 100;
        ctx.createMediaStreamSource(stream).connect(gain);
        gain.connect(analyser);
        const monitorGain = ctx.createGain();
        monitorGain.gain.value = loopback ? 1 : 0;
        gain.connect(monitorGain);
        monitorGain.connect(ctx.destination);
        if (ctx.setSinkId) await ctx.setSinkId(value("audio.output") || "").catch(() => {});
        const buffer = new Float32Array(analyser.fftSize);
        running = { stream, ctx, frame: 0, monitorGain };
        const tick = () => {
          analyser.getFloatTimeDomainData(buffer);
          let sum = 0;
          for (const v of buffer) sum += v * v;
          meter.style.width = Math.min(100, Math.sqrt(sum / buffer.length) * 400) + "%";
          running.frame = requestAnimationFrame(tick);
        };
        tick();
        start.textContent = "Parar teste";
        status.textContent = "O microfone está captando.";
      } catch (e) {
        status.textContent = "Não foi possível acessar o microfone selecionado.";
      }
    });
    chime.addEventListener("click", async () => {
      const ctx = new AudioContext();
      if (ctx.setSinkId) await ctx.setSinkId(value("audio.output") || "").catch(() => {});
      const gain = ctx.createGain();
      gain.gain.value = 0.18 * Number(value("audio.outputVolume")) / 100;
      gain.connect(ctx.destination);
      [523.25, 659.25].forEach((freq, i) => {
        const osc = ctx.createOscillator();
        osc.frequency.value = freq;
        osc.connect(gain);
        osc.start(ctx.currentTime + i * 0.22);
        osc.stop(ctx.currentTime + i * 0.22 + 0.2);
      });
      setTimeout(() => ctx.close(), 900);
    });
    return E("div", { class: "row flex-col items-stretch gap-2" }, E("div", { class: "font-semibold" }, "Teste de microfone e áudio"),
      E("div", { class: "meter" }, meter), status, loopbackOption, E("div", { class: "flex gap-2 pt-1" }, start, chime));
  }

  function videoTab(body) {
    const monitors = (AV.state.monitors || []).map((m, i) => [String(i), "Monitor " + (i + 1) + (m.primary ? " (principal)" : "")]);
    body.append(
      select("video.monitor", "Monitor", "Qual tela você compartilha.", monitors.length ? monitors : [["0", "Monitor 1"]]),
      select("video.resolution", "Resolução", "A resolução máxima da transmissão. A proporção da tela é sempre preservada.", [["720", "720p"], ["1080", "1080p Full HD"], ["1440", "1440p Quad HD"], ["native", "Original da tela"]]),
      select("video.fps", "Quadros por segundo", "60 FPS exige um computador e uma conexão que aguentem.", [["30", "30 FPS"], ["60", "60 FPS"]]),
      select("video.priority", "Prioridade", "Nitidez deixa textos e detalhes legíveis. Fluidez favorece jogos e vídeos com muito movimento.", [["detail", "Nitidez"], ["motion", "Fluidez"]]),
      toggle("video.auto", "Qualidade automática", "Reduz a resolução só para quem está com a rede ruim e volta quando melhora."),
      select("video.bitrate", "Taxa de bits máxima", "Automática respeita a resolução escolhida.", [["0", "Automática"], ["3000", "3 Mbps"], ["5000", "5 Mbps"], ["8000", "8 Mbps"], ["12000", "12 Mbps"], ["20000", "20 Mbps"], ["30000", "30 Mbps"]]),
      toggle("video.systemAudio", "Compartilhar áudio do sistema", "Envia o som do computador junto com a tela. O microfone continua separado e obedece ao botão de silenciar."));
  }

  function photoRow() {
    const preview = E("div", { class: "flex items-center gap-4" });
    const input = E("input", { type: "file", accept: "image/png,image/jpeg,image/webp,image/gif", class: "hidden", "aria-label": "Escolher foto" });
    const draw = () => AV.fill(preview, AV.avatar(AV.state.me.id, AV.state.me.name, "avatar-lg"),
      E("div", { class: "flex flex-wrap gap-2" },
        E("button", { class: "btn", onclick: () => input.click() }, "Escolher foto"),
        AV.state.me.avatar && E("button", { class: "btn btn-ghost", onclick: () => upload("") }, "Remover")));
    const upload = async (data) => {
      try {
        await AV.api("PUT", "/api/me/avatar", { data });
        await AV.refresh();
        draw();
        AV.toast(data ? "Foto atualizada." : "Foto removida.", "ok");
      } catch (e) { AV.fail(e); }
    };
    input.addEventListener("change", async () => {
      const file = input.files[0];
      input.value = "";
      if (!file) return;
      if (file.size > 5 * 1024 * 1024) { AV.toast("Escolha uma imagem de até 5 MB.", "error"); return; }
      try { upload(await cropToWebp(file)); } catch (e) { AV.toast("Não foi possível ler essa imagem.", "error"); }
    });
    draw();
    return row("Foto de perfil", "Recortada em quadrado e reduzida antes de ser enviada aos outros participantes.", E("div", {}, preview, input));
  }

  async function cropToWebp(file) {
    const bitmap = await createImageBitmap(file);
    const side = Math.min(bitmap.width, bitmap.height);
    const canvas = document.createElement("canvas");
    canvas.width = 128;
    canvas.height = 128;
    const g = canvas.getContext("2d");
    g.imageSmoothingQuality = "high";
    g.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, 128, 128);
    bitmap.close();
    const url = canvas.toDataURL("image/webp", 0.8);
    if (!url.startsWith("data:image/webp;base64,")) throw new Error("webp unsupported");
    return url.slice("data:image/webp;base64,".length);
  }

  function appTab(body) {
    const name = E("input", { class: "input w-56", maxlength: 32, value: AV.state.me.name, "aria-label": "Seu nome" });
    name.addEventListener("change", async () => {
      try { await AV.api("PUT", "/api/me", { name: name.value }); AV.toast("Nome atualizado. Ele aparece para os outros na próxima conexão.", "ok"); AV.refresh(); } catch (e) { AV.fail(e); }
    });
    body.append(
      photoRow(),
      row("Seu nome", "É como os outros participantes veem você.", name),
      toggle("autostart", "Iniciar com o Windows", AV.state.autostartAvailable ? "O aplicativo abre com a janela principal e já se conecta à rede quando você entra no Windows." : "Disponível apenas na versão instalada.", !AV.state.autostartAvailable),
      toggle("startMinimized", "Iniciar escondido na bandeja", "Abre sem mostrar a janela, mas já conectado e recebendo chamadas."),
      toggle("minimizeToTray", "Minimizar para a bandeja", "Ao minimizar, a janela sai também da barra de tarefas e fica só na bandeja."),
      toggle("closeToTray", "Fechar para a bandeja", "O botão de fechar só esconde a janela, e você continua recebendo chamadas. Para encerrar de verdade, use Sair no ícone da bandeja."),
      select("theme", "Tema", "", [["dark", "Escuro"], ["light", "Claro"]]),
      toggle("notifications", "Notificações", "Avisa sobre mensagens e entradas quando a janela está em segundo plano."),
      toggle("privacy.preview", "Mostrar o texto nas notificações", "Desligado, a notificação só diz que chegou uma mensagem."),
      toggle("privacy.shareAlias", "Compartilhar meu apelido do Radmin", "Outros participantes veem o seu apelido do Radmin VPN ao lado do nome."),
      toggle("overlay.enabled", "Mostrar quem está falando no canto da tela", "Durante uma chamada, com o Angatu Voice em segundo plano, um aviso discreto mostra quem fala."),
      toggle("overlay.transparent", "Fundo transparente nos avisos", "Remove a caixa escura de fundo, exibindo apenas as fotos e os nomes sobre a tela."),
      select("overlay.corner", "Posição do aviso", "", [["tr", "Canto superior direito"], ["tl", "Canto superior esquerdo"], ["br", "Canto inferior direito"], ["bl", "Canto inferior esquerdo"]]));
  }

  const HOTKEYS = ["ptt", "mic", "deafen", "muteAll", "call", "share", "show"];

  function hotkeysTab(body) {
    body.append(
      E("p", { class: "mb-2 text-sm text-muted" }, "Funcionam em qualquer janela, até com o Angatu Voice minimizado. Clique em um atalho e pressione as teclas; Backspace remove e Esc cancela."),
      hotkey("hotkeys.mic", "Silenciar ou ativar o microfone", "Liga e desliga o seu microfone."),
      hotkey("hotkeys.deafen", "Silenciar ou ativar o áudio", "Corta o som da chamada para você. Ao ativar de novo, o som volta."),
      hotkey("hotkeys.ptt", "Apertar para falar", "Só transmite enquanto as teclas estiverem pressionadas. Ative em Áudio."),
      hotkey("hotkeys.muteAll", "Silenciar todos", "Corta o som de todas as pessoas sem desligar o seu microfone."),
      hotkey("hotkeys.call", "Entrar ou sair da chamada", "Usa a última conversa aberta."),
      hotkey("hotkeys.share", "Compartilhar ou parar de compartilhar a tela", "Funciona durante uma chamada."),
      hotkey("hotkeys.show", "Abrir o Angatu Voice", "Traz a janela para a frente."),
      E("div", { class: "row" }, E("div", { class: "row-text" }, E("div", { class: "font-semibold" }, "Restaurar atalhos padrão"), E("small", {}, "Volta ao conjunto original de teclas.")),
        E("button", { class: "btn", onclick: async () => {
          const defaults = { "hotkeys.mic": "Ctrl+Shift+M", "hotkeys.deafen": "Ctrl+Shift+D", "hotkeys.ptt": "", "hotkeys.muteAll": "", "hotkeys.call": "", "hotkeys.share": "", "hotkeys.show": "" };
          try { await AV.api("PUT", "/api/settings", defaults); Object.assign(AV.state.settings, defaults); render(); } catch (e) { AV.fail(e); }
        } }, "Restaurar")));
  }

  function networkTab(body) {
    const r = AV.state.radmin;
    const dot = (cls) => E("span", { class: "dot " + cls });
    const topHeader = E("div", { class: "mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between" },
      E("div", {},
        E("div", { class: "font-semibold" }, "Estado do Radmin VPN"),
        E("div", { class: "text-sm text-muted" }, r.installed ? (r.running ? "Serviço ativo e operacional." : "Instalado, mas o serviço está parado.") : "Radmin VPN não encontrado no computador.")),
      E("div", { class: "flex gap-2" },
        E("button", { class: "btn btn-sm", onclick: () => AV.api("POST", "/api/networks/open-radmin").catch(AV.fail) }, AV.icon("share", "ic-sm mr-1"), "Abrir Radmin"),
        E("button", { class: "btn btn-sm", onclick: () => loadNetworks() }, AV.icon("refresh", "ic-sm mr-1"), "Atualizar")));

    const managerSection = E("div", { class: "mt-6" });

    body.append(
      topHeader,
      row("Seu apelido no Radmin", "Lido do próprio Radmin VPN.", E("span", { class: "mono" }, r.alias || "—")),
      row("IP privado", r.adapter ? "Adaptador: " + r.adapter : "", E("span", { class: "mono" }, r.ip || "—")),
      ...(AV.state.issues.length ? [E("div", { class: "banner mt-3 rounded-md" }, AV.icon("alert"), AV.state.issues.join(" "))] : []),
      managerSection);

    async function loadNetworks() {
      AV.fill(managerSection, E("div", { class: "text-muted text-sm" }, "Carregando redes…"));
      let list = [];
      try {
        list = await AV.api("GET", "/api/networks");
      } catch (e) {
        AV.fill(managerSection, E("div", { class: "banner rounded-md" }, "Não foi possível consultar as redes: " + e.message));
        return;
      }

      const def = list.find((n) => n.isDefault);
      const custom = list.filter((n) => !n.isDefault);

      const statusChip = (st) => {
        if (st === "connected") return E("span", { class: "chip chip-green" }, "Conectado");
        if (st === "connecting") return E("span", { class: "chip chip-blue" }, "Conectando…");
        if (st === "reconnecting") return E("span", { class: "chip chip-yellow" }, "Reconectando…");
        if (st === "awaiting_bootstrap") return E("span", { class: "chip chip-yellow" }, "Aguardando inicialização");
        if (st === "service_stopped") return E("span", { class: "chip chip-red" }, "Serviço parado");
        return E("span", { class: "chip" }, "Desconectado");
      };

      const defCard = def ? E("div", { class: "card p-4 border border-emerald-500/30 bg-emerald-500/5 mb-6" },
        E("div", { class: "flex items-center justify-between" },
          E("div", { class: "flex items-center gap-2" },
            E("div", { class: "text-lg font-bold" }, def.name),
            E("span", { class: "chip chip-blue text-xs" }, "Rede obrigatória do sistema")),
          statusChip(def.status)),
        E("p", { class: "text-sm text-muted mt-1" }, "Rede padrão permanente do AngatuVoice para comunicação com todos os participantes."),
        E("div", { class: "mt-3 flex flex-wrap items-center gap-4 text-sm" },
          E("div", {}, E("span", { class: "text-muted" }, "IP: "), E("span", { class: "mono font-semibold" }, def.ip || r.ip || "—")),
          E("div", {}, E("span", { class: "text-muted" }, "Participantes: "), E("span", { class: "font-semibold" }, def.onlinePeers + " online")),
          def.message && E("div", { class: "text-faint text-xs italic" }, def.message)),
        E("div", { class: "mt-4 flex gap-2" },
          E("button", { class: "btn btn-sm btn-primary", onclick: async () => {
            try {
              await AV.api("POST", "/api/networks/reconnect");
              AV.toast("Reconexão iniciada…", "ok");
              setTimeout(loadNetworks, 1000);
            } catch (err) { AV.fail(err); }
          } }, AV.icon("refresh", "ic-sm mr-1"), "Reconectar"))) : E("div");

      const customHeader = E("div", { class: "mb-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between" },
        E("h3", { class: "font-bold" }, "Outras redes Radmin (" + custom.length + ")"),
        E("div", { class: "flex gap-2" },
          E("button", { class: "btn btn-sm", onclick: openJoinModal }, AV.icon("plus", "ic-sm mr-1"), "Entrar em rede"),
          E("button", { class: "btn btn-sm", onclick: openCreateModal }, AV.icon("plus", "ic-sm mr-1"), "Criar rede")));

      const customList = custom.length ? E("div", { class: "card px-4 mb-6" },
        custom.map((n) => E("div", { class: "row py-3 flex items-center justify-between" },
          E("div", {},
            E("div", { class: "font-semibold" }, n.name),
            E("div", { class: "text-xs text-muted" }, n.status === "connected" ? "Conectada no Radmin" : "Desconectada")),
          E("div", { class: "flex items-center gap-2" },
            statusChip(n.status),
            n.status === "connected" ?
              E("button", { class: "btn btn-sm btn-ghost", onclick: async () => {
                if (await AV.confirm("Sair da rede " + n.name + "?", "Você deixará de se comunicar com os membros desta rede adicional.", "Sair")) {
                  try {
                    await AV.api("POST", "/api/networks/leave", { name: n.name });
                    AV.toast("Saída concluída.", "ok");
                    loadNetworks();
                  } catch (err) { AV.fail(err); }
                }
              } }, "Sair") :
              E("button", { class: "btn btn-sm", onclick: () => openJoinModal(n.name) }, "Conectar"),
            E("button", { class: "btn btn-sm btn-ghost text-red-500", "aria-label": "Remover do histórico", onclick: async () => {
              if (await AV.confirm("Remover " + n.name + " do histórico?", "A rede será desvinculada do AngatuVoice.", "Remover")) {
                try {
                  await AV.api("DELETE", "/api/networks/" + encodeURIComponent(n.name));
                  AV.toast("Rede removida.", "ok");
                  loadNetworks();
                } catch (err) { AV.fail(err); }
              }
            } }, AV.icon("trash", "ic-sm")))))) :
        E("p", { class: "text-sm text-muted mb-6" }, "Nenhuma rede adicional cadastrada. Você pode criar ou entrar em outras redes pelos botões acima.");

      AV.fill(managerSection, defCard, customHeader, customList);
    }

    function openJoinModal(presetName) {
      const nameInput = E("input", { class: "input w-full", placeholder: "Nome da rede", value: typeof presetName === "string" ? presetName : "", required: true });
      const passInput = E("input", { class: "input w-full", type: "password", placeholder: "Senha da rede", required: true });
      AV.modal("Entrar em rede Radmin", (b) => {
        b.append(
          E("p", { class: "text-sm text-muted mb-3" }, "Informe o nome e a senha da rede Radmin VPN que deseja ingressar."),
          E("div", { class: "space-y-3" },
            E("div", {}, E("label", { class: "text-xs font-semibold block mb-1" }, "Nome da rede"), nameInput),
            E("div", {}, E("label", { class: "text-xs font-semibold block mb-1" }, "Senha"), passInput)));
      }, [
        { label: "Cancelar", run: (close) => close() },
        { label: "Entrar", primary: true, run: async (close) => {
          const name = nameInput.value.trim();
          const pass = passInput.value;
          if (!name || !pass) { AV.toast("Preencha o nome e a senha da rede.", "warn"); return; }
          try {
            AV.toast("Conectando…", "ok");
            const res = await AV.api("POST", "/api/networks/join", { name, password: pass });
            if (res.ok) {
              AV.toast(res.message || "Conectado à rede com sucesso!", "ok");
              close();
              loadNetworks();
            } else {
              AV.toast(res.message || "Não foi possível entrar na rede.", "warn");
            }
          } catch (e) { AV.fail(e); }
        } }
      ]);
    }

    function openCreateModal() {
      const nameInput = E("input", { class: "input w-full", placeholder: "Nome da nova rede", required: true });
      const passInput = E("input", { class: "input w-full", type: "password", placeholder: "Senha (mínimo 6 caracteres)", required: true });
      const confirmInput = E("input", { class: "input w-full", type: "password", placeholder: "Confirmar senha", required: true });
      AV.modal("Criar nova rede Radmin", (b) => {
        b.append(
          E("p", { class: "text-sm text-muted mb-3" }, "Crie uma nova rede privada no Radmin VPN sob sua administração."),
          E("div", { class: "space-y-3" },
            E("div", {}, E("label", { class: "text-xs font-semibold block mb-1" }, "Nome da rede"), nameInput),
            E("div", {}, E("label", { class: "text-xs font-semibold block mb-1" }, "Senha"), passInput),
            E("div", {}, E("label", { class: "text-xs font-semibold block mb-1" }, "Confirmar senha"), confirmInput)));
      }, [
        { label: "Cancelar", run: (close) => close() },
        { label: "Criar rede", primary: true, run: async (close) => {
          const name = nameInput.value.trim();
          const pass = passInput.value;
          const confirm = confirmInput.value;
          if (!name || !pass) { AV.toast("Preencha todos os campos.", "warn"); return; }
          if (pass.length < 6) { AV.toast("A senha deve ter no mínimo 6 caracteres.", "warn"); return; }
          if (pass !== confirm) { AV.toast("As senhas informadas não coincidem.", "warn"); return; }
          try {
            AV.toast("Criando rede…", "ok");
            const res = await AV.api("POST", "/api/networks/create", { name, password: pass });
            if (res.ok) {
              AV.toast(res.message || "Rede criada com sucesso!", "ok");
              close();
              loadNetworks();
            } else {
              AV.toast(res.message || "Não foi possível criar a rede.", "warn");
            }
          } catch (e) { AV.fail(e); }
        } }
      ]);
    }

    loadNetworks();

    const peers = AV.state.peers;
    body.append(E("h3", { class: "mt-6 mb-2 font-bold" }, "Participantes conhecidos (" + peers.filter((p) => p.online).length + " online)"),
      peers.length ? E("div", { class: "card px-4" }, peers.map((p) => E("div", { class: "row" },
        E("div", { class: "flex items-center gap-3" }, AV.avatar(p.id, p.name, "avatar-sm", p.online),
          E("div", {}, E("div", { class: "font-semibold" }, p.name, p.duplicateName && E("span", { class: "chip ml-2" }, "nome repetido")),
            E("small", { class: "mono text-muted" }, p.id + (p.ip ? "  ·  " + p.ip : "")))),
        E("span", { class: "chip" }, p.online ? "Conectado" : "Offline")))) : E("p", { class: "text-muted" }, "Ninguém foi encontrado ainda. Peça para as outras pessoas abrirem o Angatu Voice."));
    const address = E("input", { class: "input", placeholder: "26.10.20.30", "aria-label": "IP do participante", maxlength: 21 });
    body.append(E("h3", { class: "mt-6 mb-2 font-bold" }, "Conectar por IP"),
      E("p", { class: "mb-2 text-sm text-muted" }, "Se a descoberta automática não achar alguém, informe o IP dele no Radmin VPN."),
      E("div", { class: "flex gap-2" }, address, E("button", { class: "btn btn-primary", onclick: async () => {
        try { await AV.api("POST", "/api/peers", { address: address.value.trim() }); AV.toast("Tentando conectar…", "ok"); address.value = ""; } catch (e) { AV.fail(e); }
      } }, "Conectar")),
      E("button", { class: "btn mt-6", onclick: async () => {
        try {
          const d = await AV.api("GET", "/api/system/diagnostics");
          d.call = AV.rtc.diagnostics();
          AV.modal("Diagnóstico", (b) => b.append(E("pre", { class: "selectable mono whitespace-pre-wrap" }, JSON.stringify(d, null, 2))), [{ label: "Fechar", run: (close) => close() }]);
        } catch (e) { AV.fail(e); }
      } }, "Executar diagnóstico"));
  }

  async function updateTab(body) {
    const u = AV.state.update;
    const history = await AV.api("GET", "/api/update").then((d) => d.history).catch(() => []);
    const busy = ["checking", "downloading", "validating", "installing", "restarting"].includes(u.state);
    const last = u.lastCheck ? new Date(u.lastCheck).toLocaleString("pt-BR") : "Nunca";
    const bar = E("i");
    bar.style.width = Math.round(u.progress * 100) + "%";
    body.append(
      E("div", { class: "card p-4" },
        E("div", { class: "flex items-center justify-between" },
          E("div", {}, E("div", { class: "text-lg font-bold" }, "Versão " + u.current), E("div", { class: "text-muted" }, u.message || UPDATE_TEXT[u.state] || "")),
          E("span", { class: "chip" }, "Canal " + (value("update.channel") === "beta" ? "beta" : "estável"))),
        u.available && E("div", { class: "mt-3" }, "Versão disponível: ", E("b", {}, u.available), u.size ? " (" + AV.bytes(u.size) + ")" : ""),
        u.state === "downloading" && E("div", { class: "mt-3" }, E("div", { class: "progress" }, bar), E("small", { class: "text-muted" }, AV.bytes(u.received) + " de " + AV.bytes(u.size))),
        u.notes && E("pre", { class: "selectable mt-3 whitespace-pre-wrap text-sm text-muted" }, u.notes),
        E("div", { class: "mt-4 flex flex-wrap gap-2" },
          E("button", { class: "btn", disabled: busy, onclick: () => AV.api("POST", "/api/update/check").catch(AV.fail) }, AV.icon("refresh", "ic-sm"), "Verificar atualizações"),
          u.canInstall && E("button", { class: "btn btn-primary", onclick: () => AV.api("POST", "/api/update/install").catch(AV.fail) }, AV.icon("download", "ic-sm"), "Instalar e reiniciar"),
          u.state === "downloading" && E("button", { class: "btn", onclick: () => AV.api("POST", "/api/update/cancel").catch(AV.fail) }, "Cancelar download")),
        E("small", { class: "mt-3 block text-faint" }, "Última verificação: " + last)),
      toggle("update.auto", "Atualizar automaticamente", "Baixa novas versões e instala quando você não estiver em uma chamada."),
      select("update.intervalHours", "Verificar a cada", "", [["1", "1 hora"], ["6", "6 horas"], ["12", "12 horas"], ["24", "24 horas"], ["168", "7 dias"]]),
      row("Canal de atualização", "O canal Beta recebe versões em teste, que podem ter falhas.",
        E("select", { class: "input w-56", "aria-label": "Canal", onchange: async (e) => {
          if (e.target.value === "beta" && !(await AV.confirm("Entrar no canal Beta?", "Versões beta ainda estão em teste e podem ter falhas.", "Entrar no Beta"))) { e.target.value = "stable"; return; }
          await save("update.channel", e.target.value);
        } }, [["stable", "Estável (recomendado)"], ["beta", "Beta"]].map(([v, l]) => E("option", { value: v, selected: v === value("update.channel") }, l)))),
      E("h3", { class: "mt-6 mb-2 font-bold" }, "Histórico de atualizações"),
      history.length ? E("div", { class: "card px-4" }, history.map((h) => E("div", { class: "row" },
        E("div", {}, E("div", { class: "font-semibold" }, "Versão " + h.version), E("small", { class: "text-muted" }, new Date(h.at).toLocaleString("pt-BR"))),
        E("span", { class: "chip" }, h.outcome === "ok" ? "Concluída" : h.outcome === "rolled_back" ? "Revertida" : "Falhou")))) : E("p", { class: "text-muted" }, "Nenhuma atualização instalada ainda."));
  }

  function aboutTab(body) {
    body.append(E("div", { class: "flex flex-col items-center gap-3 py-8 text-center" },
      E("img", { src: "/assets/icon-256.png", width: 96, height: 96, alt: "Angatu Voice", draggable: "false" }),
      E("div", { class: "text-xl font-bold" }, "Angatu Voice"), E("div", { class: "text-muted" }, "Versão " + AV.state.version),
      E("p", { class: "max-w-sm text-muted" }, "Conversa privada por voz, texto e tela sobre a sua rede do Radmin VPN."),
      E("div", { class: "mt-2 flex items-center gap-2 text-sm text-faint" }, E("img", { src: "/assets/icon-32.png", width: 18, height: 18, alt: "", draggable: "false" }), "Desenvolvido por Angatu Sistemas"),
      E("button", { class: "btn btn-danger mt-4", onclick: async () => { if (await AV.confirm("Sair do Angatu Voice?", "Você deixará de receber mensagens e chamadas até abrir o aplicativo de novo.", "Sair", true)) AV.api("POST", "/api/app/quit").catch(() => {}); } }, AV.icon("power", "ic-sm"), "Sair do aplicativo")));
  }

  const TABS = [["app", "Aplicativo", appTab], ["audio", "Áudio", audioTab], ["hotkeys", "Atalhos", hotkeysTab], ["video", "Vídeo", videoTab],
    ["network", "Rede", networkTab], ["update", "Atualizações", updateTab], ["about", "Sobre", aboutTab]];

  async function render() {
    if (!panel) return;
    if (stopMicTest) stopMicTest();
    const content = panel.querySelector("[data-content]");
    const body = E("div", { class: "mx-auto max-w-2xl" });
    const [, title, build] = TABS.find((t) => t[0] === current);
    body.append(E("h2", { class: "mb-2 text-xl font-bold" }, title));
    await build(body);
    content.replaceChildren(body);
    panel.querySelectorAll("[role=tab]").forEach((t) => t.setAttribute("aria-selected", String(t.dataset.tab === current)));
  }

  AV.openSettings = function (tab) {
    if (panel) return;
    current = tab || "app";
    const close = () => { if (stopMicTest) stopMicTest(); panel.remove(); panel = null; document.removeEventListener("keydown", onKey); };
    const onKey = (e) => { if (e.key === "Escape" && !document.querySelector(".overlay")) close(); };
    panel = E("div", { class: "fixed inset-0 z-40 flex flex-col bg-bg0 md:flex-row", role: "dialog", "aria-label": "Configurações" },
      E("nav", { class: "flex flex-none gap-1 overflow-x-auto border-b border-line bg-bg1 p-3 md:w-64 md:flex-col md:border-b-0 md:border-r", role: "tablist" },
        E("div", { class: "hidden px-3 pb-2 pt-3 text-xs font-bold uppercase tracking-wider text-faint md:block" }, "Configurações"),
        TABS.map(([id, label]) => E("button", { class: "tab", role: "tab", dataset: { tab: id }, onclick: () => { current = id; render(); } }, label))),
      E("div", { class: "relative min-h-0 flex-1 overflow-y-auto p-6 md:p-10", "data-content": "" }),
      E("button", { class: "btn btn-ghost btn-icon absolute right-4 top-4", "aria-label": "Fechar configurações", onclick: close }, AV.icon("x")));
    document.getElementById("overlay-root").append(panel);
    document.addEventListener("keydown", onKey);
    render();
  };

  AV.refreshSettings = function () { if (panel) render(); };
  AV.settingsOpen = () => !!panel;
})();
