"use strict";
/* Shared helpers: API client, DOM builder, icons, avatars, toasts and modals. */
window.AV = window.AV || {};

AV.api = async function (method, path, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, options);
  } catch (e) {
    throw new Error("Sem conexão com o aplicativo.");
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!response.ok) {
    const error = new Error((data && (data.title || data.message)) || text || "Falha na operação.");
    error.status = response.status;
    throw error;
  }
  return data;
};

AV.el = function (tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === false || value === null || value === undefined) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "dataset") Object.assign(node.dataset, value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
};

AV.fill = function (node, ...children) {
  node.replaceChildren();
  for (const child of children.flat(3)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
};

AV.icon = function (name, extra) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "ic" + (extra ? " " + extra : ""));
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
};

AV.colorFor = function (id) {
  let hash = 0;
  for (const ch of String(id)) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return "hsl(" + (hash % 360) + " 52% 62%)";
};

AV.avatar = function (id, name, size, online) {
  const node = AV.el("div", { class: "avatar " + (size || ""), style: "background:" + AV.colorFor(id), "aria-hidden": "true" },
    (name || "?").trim().charAt(0).toUpperCase());
  if (online !== undefined) node.append(AV.el("span", { class: "presence" + (online ? " on" : "") }));
  return node;
};

AV.toast = function (message, kind) {
  const node = AV.el("div", { class: "toast " + (kind || ""), role: "status" }, message);
  document.getElementById("toasts").append(node);
  setTimeout(() => {
    node.classList.add("leaving");
    setTimeout(() => node.remove(), 220);
  }, kind === "error" ? 6000 : 3600);
};

AV.fail = function (error) {
  AV.toast(error && error.message ? error.message : "Algo deu errado.", "error");
};

AV.modal = function (title, build, actions) {
  const root = document.getElementById("overlay-root");
  const close = () => {
    overlay.remove();
    document.removeEventListener("keydown", onKey);
  };
  const onKey = (e) => { if (e.key === "Escape") close(); };
  const body = AV.el("div", { class: "mt-4" });
  build(body, close);
  const footer = AV.el("div", { class: "mt-5 flex justify-end gap-2" },
    (actions || []).map((a) => AV.el("button", {
      class: "btn " + (a.primary ? "btn-primary" : a.danger ? "btn-danger" : ""),
      onclick: () => a.run(close, body),
    }, a.label)));
  const overlay = AV.el("div", { class: "overlay", onmousedown: (e) => { if (e.target === overlay) close(); } },
    AV.el("div", { class: "modal card", role: "dialog", "aria-modal": "true", "aria-label": title },
      AV.el("div", { class: "flex items-center justify-between" },
        AV.el("h2", { class: "text-lg font-bold" }, title),
        AV.el("button", { class: "btn btn-ghost btn-icon", "aria-label": "Fechar", onclick: close }, AV.icon("x"))),
      body, footer));
  document.addEventListener("keydown", onKey);
  root.append(overlay);
  const first = overlay.querySelector("input, textarea, select, button.btn-primary");
  if (first) first.focus();
  return close;
};

AV.confirm = function (title, text, label, danger) {
  return new Promise((resolve) => {
    AV.modal(title, (body) => body.append(AV.el("p", { class: "text-muted" }, text)), [
      { label: "Cancelar", run: (close) => { close(); resolve(false); } },
      { label, primary: !danger, danger: !!danger, run: (close) => { close(); resolve(true); } },
    ]);
  });
};

AV.time = function (ts) {
  return new Date(ts).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
};

AV.day = function (ts) {
  const d = new Date(ts);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86400000);
  if (d.toDateString() === today.toDateString()) return "Hoje";
  if (d.toDateString() === yesterday.toDateString()) return "Ontem";
  return d.toLocaleDateString("pt-BR", { day: "2-digit", month: "long", year: "numeric" });
};

AV.bytes = function (n) {
  if (n >= 1048576) return (n / 1048576).toFixed(1).replace(".", ",") + " MB";
  return Math.max(1, Math.round(n / 1024)) + " KB";
};

AV.debounce = function (fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
};
