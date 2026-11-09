"use strict";
/* Message formatting without HTML: the text is parsed into elements built from text nodes, so nothing a person writes
   can become markup. Also the emoji list with the names used by the picker and the :name: autocomplete. */
(function () {
  const E = AV.el;
  const URL_RE = /https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]}]/;

  /* Earliest match wins; on a tie, the first rule in this list. Code and links keep their content as plain text. */
  const INLINE = [
    { re: /\\([*_~`|\\<>#[\]()-])/, build: (m) => m[1] },
    { re: /`([^`\n]+)`/, build: (m) => E("code", { class: "md-code" }, m[1]) },
    { re: /<@([0-9a-f]{20})>/, build: (m, o) => mention(m[1], o) },
    { re: /!\[([^\]\n]{0,200})\]\((https?:\/\/[^\s)]+)\)/, build: (m, o) => link(m[2], ["Imagem: " + (m[1] || m[2])], o) },
    { re: /\[([^\]\n]{1,200})\]\((https?:\/\/[^\s)]+)\)/, build: (m, o) => link(m[2], inline(m[1], o), o) },
    { re: URL_RE, build: (m, o) => link(m[0], [m[0]], o) },
    { re: /\*\*([\s\S]+?)\*\*/, build: (m, o) => E("strong", {}, inline(m[1], o)) },
    { re: /__([\s\S]+?)__/, build: (m, o) => E("u", {}, inline(m[1], o)) },
    { re: /~~([\s\S]+?)~~/, build: (m, o) => E("s", {}, inline(m[1], o)) },
    { re: /\|\|([\s\S]+?)\|\|/, build: (m, o) => spoiler(inline(m[1], o)) },
    { re: /\*([^*\s][^*]*?)\*/, build: (m, o) => E("em", {}, inline(m[1], o)) },
    { re: /(?<![\w])_([^_\s][^_]*?)_(?![\w])/, build: (m, o) => E("em", {}, inline(m[1], o)) },
  ];

  function inline(text, o) {
    const out = [];
    let rest = text;
    while (rest) {
      let best = null;
      for (const rule of INLINE) {
        const m = rule.re.exec(rest);
        if (m && (!best || m.index < best.m.index)) best = { m, rule };
      }
      if (!best) { out.push(rest); break; }
      if (best.m.index > 0) out.push(rest.slice(0, best.m.index));
      out.push(best.rule.build(best.m, o));
      rest = rest.slice(best.m.index + best.m[0].length);
    }
    return out;
  }

  function mention(id, o) {
    const self = o.me === id;
    return E("span", { class: "md-mention" + (self ? " md-mention-me" : ""), title: o.nameOf(id) }, "@" + o.nameOf(id));
  }

  function link(url, children, o) {
    return E("a", { class: "md-link", href: url, title: url, onclick: (e) => { e.preventDefault(); o.openLink(url); } }, children);
  }

  function spoiler(children) {
    const node = E("span", { class: "md-spoiler", role: "button", tabindex: 0, "aria-label": "Conteúdo oculto. Clique para mostrar." }, children);
    const reveal = (e) => { e.stopPropagation(); node.classList.add("open"); node.removeAttribute("role"); node.removeAttribute("aria-label"); };
    node.addEventListener("click", reveal);
    node.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") reveal(e); });
    return node;
  }

  /* Lines become blocks (headers, small text, quotes, lists); everything else stays as text with its line breaks. */
  function blocks(text, o) {
    const out = [];
    const lines = text.split("\n");
    let plain = [];
    const flush = () => {
      if (plain.length) { out.push(E("span", {}, inline(plain.join("\n"), o))); plain = []; }
    };
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      let m;
      if (line.startsWith(">>> ")) {
        flush();
        out.push(E("blockquote", { class: "md-quote" }, blocks([line.slice(4), ...lines.slice(i + 1)].join("\n"), o)));
        break;
      }
      if (line.startsWith("> ") || line === ">") {
        flush();
        const quoted = [];
        while (i < lines.length && (lines[i].startsWith("> ") || lines[i] === ">")) quoted.push(lines[i++].slice(2));
        i--;
        out.push(E("blockquote", { class: "md-quote" }, blocks(quoted.join("\n"), o)));
      } else if ((m = /^(#{1,3}) (.+)$/.exec(line))) {
        flush();
        out.push(E("div", { class: "md-h" + m[1].length, role: "heading", "aria-level": m[1].length + 2 }, inline(m[2], o)));
      } else if ((m = /^-# (.+)$/.exec(line))) {
        flush();
        out.push(E("div", { class: "md-sub" }, inline(m[1], o)));
      } else if (/^\s*([-*]|\d{1,3}\.) \S/.test(line)) {
        flush();
        const ordered = /^\s*\d/.test(line);
        const items = [];
        while (i < lines.length && /^\s*([-*]|\d{1,3}\.) \S/.test(lines[i]) && /^\s*\d/.test(lines[i]) === ordered) {
          items.push(E("li", {}, inline(lines[i++].replace(/^\s*([-*]|\d{1,3}\.) /, ""), o)));
        }
        i--;
        out.push(E(ordered ? "ol" : "ul", { class: "md-list" }, items));
      } else {
        plain.push(line);
      }
    }
    flush();
    return out;
  }

  /* ---- code blocks: a header with the language and a copy button, and light colouring done on text nodes ---- */

  const KEYWORDS = new Set(("if else for while do return function const let var class interface enum public private protected static final "
    + "void new import from export default def lambda async await try catch finally throw throws extends implements package record "
    + "switch case break continue in of not and or is as with yield struct fn pub mut use impl match where select insert update "
    + "delete into values create table set null true false none None True False nil this self super echo local then fi done end "
    + "int long double float boolean bool string char byte short var val type typeof instanceof sealed permits module").split(" "));
  const TOKEN = /(\/\/[^\n]*|#(?![{!])[^\n]*|--[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][\w]*\b)/g;
  const COMMENT_HASH = /^(py|python|sh|bash|shell|yaml|yml|ruby|rb|r|toml|ini|conf|dockerfile|make|makefile|powershell|ps1)$/i;
  const COMMENT_DASH = /^(sql|lua|haskell|hs)$/i;

  function highlight(code, lang) {
    const out = [];
    let last = 0;
    for (const m of code.matchAll(TOKEN)) {
      const t = m[0];
      let cls = null;
      if (t.startsWith("//") || t.startsWith("/*")) cls = "tk-com";
      else if (t.startsWith("#")) cls = COMMENT_HASH.test(lang) ? "tk-com" : null;
      else if (t.startsWith("--")) cls = COMMENT_DASH.test(lang) ? "tk-com" : null;
      else if (/^["'`]/.test(t)) cls = "tk-str";
      else if (/^\d/.test(t)) cls = "tk-num";
      else if (KEYWORDS.has(t)) cls = "tk-key";
      else if (/^[A-Z][a-z0-9]+[A-Z]?\w*$/.test(t)) cls = "tk-type";
      if (!cls) continue;
      if (m.index > last) out.push(code.slice(last, m.index));
      out.push(E("span", { class: cls }, t));
      last = m.index + t.length;
    }
    if (last < code.length) out.push(code.slice(last));
    return out;
  }

  function codeBlock(body, lang) {
    const copy = E("button", { class: "md-code-copy", type: "button", "aria-label": "Copiar código" }, AV.icon("copy", "ic-sm"), "Copiar");
    copy.addEventListener("click", (e) => {
      e.stopPropagation();
      navigator.clipboard.writeText(body).then(() => {
        AV.fill(copy, AV.icon("check", "ic-sm"), "Copiado");
        setTimeout(() => AV.fill(copy, AV.icon("copy", "ic-sm"), "Copiar"), 1600);
      });
    });
    return E("div", { class: "md-codeblock" },
      E("div", { class: "md-codeblock-head" }, E("span", {}, lang || "código"), copy),
      E("pre", { class: "md-pre" }, E("code", {}, body.length <= 60000 ? highlight(body, lang) : body)));
  }

  /* Fences first, so no other rule runs inside them. An unclosed fence is just text. */
  function splitFences(text, prose) {
    const out = [];
    const parts = text.split("```");
    parts.forEach((part, i) => {
      const closed = i % 2 === 1 && i < parts.length - 1;
      if (!closed) {
        const t = i % 2 === 1 ? "```" + part : part;
        if (t) out.push(...prose(t));
        return;
      }
      let body = part;
      let lang = "";
      const nl = part.indexOf("\n");
      if (nl >= 0 && /^[\w+#.-]{0,20}$/.test(part.slice(0, nl).trim())) { lang = part.slice(0, nl).trim(); body = part.slice(nl + 1); }
      out.push(codeBlock(body.replace(/\n$/, ""), lang));
    });
    return out;
  }

  /* A chat message: the Discord-like subset, line breaks kept as written. */
  function render(text, o) {
    return splitFences(text, (t) => blocks(t, o));
  }

  /* ---- documents (.md files): the full Markdown people paste from assistants and wikis ---- */

  const LIST = /^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$/;
  const TABLE_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

  function cells(line) {
    return line.trim().replace(/^\|/, "").replace(/\|$/, "").split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
  }

  function table(lines, o) {
    const head = cells(lines[0]);
    const align = cells(lines[1]).map((c) => (c.startsWith(":") && c.endsWith(":") ? "center" : c.endsWith(":") ? "right" : "left"));
    const cell = (tag, text, i) => E(tag, { style: "text-align:" + (align[i] || "left") }, inline(text, o));
    return E("div", { class: "md-table-wrap" }, E("table", { class: "md-table" },
      E("thead", {}, E("tr", {}, head.map((h, i) => cell("th", h, i)))),
      E("tbody", {}, lines.slice(2).map((row) => E("tr", {}, head.map((_, i) => cell("td", cells(row)[i] || "", i)))))));
  }

  /* Indentation decides nesting; a "[ ]" or "[x]" right after the marker makes a task. */
  function list(lines, o) {
    const root = { children: [], indent: -1 };
    const stack = [root];
    for (const line of lines) {
      const m = LIST.exec(line);
      if (!m) {
        const top = stack[stack.length - 1];
        if (top.items && top.items.length) top.items[top.items.length - 1].text += "\n" + line.trim();
        continue;
      }
      const indent = m[1].replace(/\t/g, "    ").length;
      const ordered = /\d/.test(m[2]);
      while (stack.length > 1 && indent < stack[stack.length - 1].indent) stack.pop();
      let top = stack[stack.length - 1];
      if (indent > top.indent && (top === root || top.items.length)) {
        const parentItem = top === root ? null : top.items[top.items.length - 1];
        const level = { indent, ordered, items: [], start: ordered ? parseInt(m[2], 10) : 1 };
        (parentItem ? parentItem.sub : root.children).push(level);
        stack.push(level);
        top = level;
      }
      top.items.push({ text: m[3], sub: [] });
    }
    const build = (level) => E(level.ordered ? "ol" : "ul", { class: "md-list", start: level.ordered && level.start !== 1 ? String(level.start) : false },
      level.items.map((item) => {
        const task = /^\[([ xX])\]\s+(.*)$/s.exec(item.text);
        const box = task && E("input", { type: "checkbox", disabled: true, class: "md-task", "aria-label": task[1] === " " ? "Pendente" : "Feito" });
        if (box) box.checked = task[1] !== " ";
        return E("li", { class: task ? "md-task-item" : false }, box, lines2(task ? task[2] : item.text, o), item.sub.map(build));
      }));
    return root.children.map(build);
  }

  function lines2(text, o) {
    const parts = text.split("\n");
    return parts.flatMap((p, i) => (i ? [E("br"), ...inline(p, o)] : inline(p, o)));
  }

  function docBlocks(text, o) {
    const out = [];
    const lines = text.split("\n");
    let para = [];
    const flush = () => { if (para.length) { out.push(E("p", {}, lines2(para.join("\n"), o))); para = []; } };
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      let m;
      if (!line.trim()) { flush(); continue; }
      if ((m = /^(#{1,6})\s+(.+?)\s*#*$/.exec(line))) {
        flush();
        out.push(E("h" + m[1].length, { class: "md-doc-h" }, inline(m[2], o)));
      } else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
        flush();
        out.push(E("hr", { class: "md-hr" }));
      } else if (line.includes("|") && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1])) {
        flush();
        const rows = [line, lines[i + 1]];
        i += 2;
        while (i < lines.length && lines[i].includes("|") && lines[i].trim()) rows.push(lines[i++]);
        i--;
        out.push(table(rows, o));
      } else if (/^\s*>/.test(line)) {
        flush();
        const quoted = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) quoted.push(lines[i++].replace(/^\s*>\s?/, ""));
        i--;
        const callout = /^\[!(\w+)\]\s*(.*)$/.exec(quoted[0] || "");
        if (callout) {
          out.push(E("div", { class: "md-callout md-callout-" + callout[1].toLowerCase() },
            E("div", { class: "md-callout-title" }, callout[2] || callout[1]), docBlocks(quoted.slice(1).join("\n"), o)));
        } else {
          out.push(E("blockquote", { class: "md-quote" }, docBlocks(quoted.join("\n"), o)));
        }
      } else if (LIST.test(line)) {
        flush();
        const items = [];
        while (i < lines.length && lines[i].trim() && (LIST.test(lines[i]) || /^\s{2,}\S/.test(lines[i]))) items.push(lines[i++]);
        i--;
        out.push(...list(items, o));
      } else {
        para.push(line);
      }
    }
    flush();
    return out;
  }

  function document(text, o) {
    return E("div", { class: "md-doc" }, splitFences(text, (t) => docBlocks(t, o)));
  }

  /* A single line for reply previews and notifications: no formatting, mentions as names. */
  function plain(text, nameOf) {
    return text.replace(/```[\s\S]*?```/g, " [código] ").replace(/<@([0-9a-f]{20})>/g, (_, id) => "@" + nameOf(id))
      .replace(/[*_~`|>#]/g, "").replace(/\s+/g, " ").trim();
  }

  const EMOJI_DATA = [
    "😀 sorriso feliz grin", "😃 sorrindo alegre smiley", "😄 riso smile", "😁 dentes grin", "😆 gargalhada laughing", "😅 suor alivio sweat",
    "😂 chorando_de_rir joy risos", "🤣 rolando_de_rir rofl", "🙂 leve slight_smile", "🙃 de_cabeca_para_baixo upside", "😉 piscada wink",
    "😊 corado blush", "😇 anjo innocent", "🥰 apaixonado coracoes", "😍 olhos_de_coracao heart_eyes", "🤩 deslumbrado star_struck",
    "😘 beijo kiss", "😋 delicia yum", "😛 lingua tongue", "😜 piscando_lingua", "🤪 maluco zany", "🤑 dinheiro money",
    "🤗 abraco hug", "🤭 ops risadinha", "🤫 silencio shush", "🤔 pensando thinking", "🤐 boca_fechada zipper", "🤨 desconfiado raised_eyebrow",
    "😐 neutro neutral", "😑 sem_expressao", "😶 sem_boca", "😏 malicioso smirk", "😒 entediado unamused", "🙄 revirando_olhos eye_roll",
    "😬 constrangido grimacing", "😌 aliviado relieved", "😔 pensativo pensive", "😪 sonolento sleepy", "😴 dormindo sleeping",
    "😷 mascara mask", "🤒 doente sick", "🤢 enjoado nauseated", "🤮 vomitando", "🥵 calor hot", "🥶 frio cold", "😵 tonto dizzy",
    "🤯 cabeca_explodindo mind_blown", "🤠 cowboy", "🥳 festa party", "😎 oculos cool sunglasses", "🤓 nerd", "🧐 monoculo",
    "😕 confuso confused", "😟 preocupado worried", "🙁 triste_leve", "😮 surpreso open_mouth", "😯 espantado hushed", "😲 chocado astonished",
    "😳 envergonhado flushed", "🥺 pidao pleading", "😦 boquiaberto", "😨 com_medo fearful", "😰 ansioso anxious", "😥 decepcionado",
    "😢 chorando cry", "😭 aos_prantos sob", "😱 gritando scream", "😖 confuso_forte", "😣 perseverante", "😞 desapontado disappointed",
    "😓 suando", "😩 cansado weary", "😫 exausto tired", "🥱 bocejo yawn", "😤 bufando triumph", "😡 bravo raiva rage", "😠 irritado angry",
    "🤬 xingando cursing", "😈 diabinho smiling_imp", "💀 caveira skull", "💩 coco poop", "🤡 palhaco clown", "👻 fantasma ghost",
    "👽 alien", "🤖 robo robot", "👋 tchau oi wave", "🤚 mao_levantada", "✋ mao hand", "🖖 vulcano", "👌 ok", "🤌 italiano pinched",
    "✌️ paz vitoria v", "🤞 dedos_cruzados sorte", "🤟 te_amo love_you", "🤘 rock", "🤙 me_liga call_me", "👈 esquerda", "👉 direita",
    "👆 cima", "👇 baixo", "☝️ indicador", "👍 joinha like positivo thumbsup", "👎 negativo dislike thumbsdown", "✊ punho fist",
    "👊 soco punch", "👏 palmas clap", "🙌 celebrando raised_hands", "👐 maos_abertas", "🤝 aperto_de_mao handshake", "🙏 por_favor obrigado pray",
    "💪 forca muscle", "🧠 cerebro brain", "👀 olhos eyes", "👁️ olho", "🗣️ falando speaking", "🫡 continencia salute",
    "❤️ coracao heart", "🧡 laranja_coracao", "💛 amarelo_coracao", "💚 verde_coracao", "💙 azul_coracao", "🖤 preto_coracao",
    "🤍 branco_coracao", "💔 coracao_partido broken_heart", "💕 dois_coracoes", "💖 brilhando_coracao", "💯 cem 100", "💢 raiva_simbolo",
    "💥 explosao boom", "💫 tontura", "💦 gotas", "💨 vento dash", "💬 balao fala speech", "💭 pensamento thought", "💤 sono zzz",
    "🔥 fogo fire", "✨ brilho sparkles", "⭐ estrela star", "🌟 estrela_brilhante", "⚡ raio zap", "☀️ sol sun", "🌙 lua moon",
    "🌈 arco_iris rainbow", "☁️ nuvem cloud", "❄️ neve snow", "🌊 onda wave_water", "🌱 broto", "🌻 girassol", "🌹 rosa", "🍀 trevo sorte",
    "🐶 cachorro dog", "🐱 gato cat", "🐭 rato", "🐰 coelho", "🦊 raposa", "🐻 urso", "🐼 panda", "🐸 sapo frog", "🐵 macaco", "🙈 nao_vejo",
    "🙉 nao_ouco", "🙊 nao_falo", "🐔 galinha", "🐧 pinguim", "🦆 pato", "🦉 coruja", "🐢 tartaruga", "🐍 cobra", "🦖 dinossauro",
    "🍕 pizza", "🍔 hamburguer burger", "🍟 batata fries", "🌭 cachorro_quente", "🍿 pipoca popcorn", "🍩 rosquinha donut", "🍪 biscoito",
    "🎂 bolo cake", "🍫 chocolate", "🍓 morango", "🍉 melancia", "🍌 banana", "🍎 maca apple", "🥑 abacate", "🌶️ pimenta", "☕ cafe coffee",
    "🍺 cerveja beer", "🍻 brinde cheers", "🥂 tacas", "🍷 vinho", "🧃 suco", "🥤 refrigerante",
    "⚽ futebol", "🏀 basquete", "🏆 trofeu trophy", "🥇 ouro medalha", "🎮 videogame game", "🕹️ joystick", "🎲 dado", "🎯 alvo",
    "🎧 fone headphones", "🎤 microfone mic", "🎵 nota musica", "🎶 notas musicas", "🎸 guitarra", "🥁 bateria", "🎬 claquete filme",
    "📷 camera", "💻 notebook laptop", "🖥️ computador desktop", "⌨️ teclado", "🖱️ mouse", "📱 celular phone", "🔋 bateria_cheia",
    "💡 ideia lampada idea", "🔧 chave_inglesa", "🔨 martelo", "⚙️ engrenagem", "🧩 quebra_cabeca", "🔒 cadeado lock", "🔑 chave key",
    "📌 alfinete pin", "📎 clipe", "✏️ lapis", "📝 anotacao memo", "📅 calendario", "⏰ despertador", "⏳ ampulheta", "📦 caixa",
    "🎁 presente gift", "🎉 confete tada", "🎊 festa_confete", "🎈 balao", "🚀 foguete rocket", "✈️ aviao", "🚗 carro", "🏠 casa",
    "🌍 mundo terra", "🗺️ mapa", "✅ certo feito check", "☑️ marcado", "✔️ visto", "❌ errado x", "❎ cancelar", "⚠️ atencao warning",
    "🚫 proibido", "⛔ pare", "❓ pergunta question", "❗ exclamacao", "‼️ dupla_exclamacao", "⁉️ interrobang", "🆗 ok_botao", "🆕 novo new",
    "🔴 vermelho", "🟠 laranja", "🟡 amarelo", "🟢 verde", "🔵 azul", "🟣 roxo", "⚫ preto", "⚪ branco", "➡️ seta_direita", "⬅️ seta_esquerda",
    "⬆️ seta_cima", "⬇️ seta_baixo", "🔁 repetir", "🔄 atualizar", "➕ mais", "➖ menos", "✖️ vezes", "💲 cifrao", "🇧🇷 brasil",
  ];

  const EMOJIS = EMOJI_DATA.map((row) => {
    const [char, ...names] = row.split(" ");
    return { char, names, code: names[0] };
  });

  const fold = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

  function search(query, limit) {
    const q = fold(query);
    if (!q) return EMOJIS.slice(0, limit);
    const starts = [];
    const contains = [];
    for (const e of EMOJIS) {
      if (e.names.some((n) => n.startsWith(q))) starts.push(e);
      else if (e.names.some((n) => n.includes(q))) contains.push(e);
    }
    return [...starts, ...contains].slice(0, limit);
  }

  /* :name: typed in full becomes the emoji when the message is sent. */
  function shortcodes(text) {
    return text.replace(/:([a-z0-9_]{2,30}):/g, (all, name) => {
      const hit = EMOJIS.find((e) => e.names.includes(name));
      return hit ? hit.char : all;
    });
  }

  AV.md = { render, plain, document };
  AV.emoji = { all: EMOJIS, search, shortcodes, fold };
})();
