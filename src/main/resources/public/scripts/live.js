"use strict";
/* One WebSocket per tab. Events are pushed notifications; whoever listens re-reads from the REST routes when it matters. */
(function () {
  const SESSION_EXPIRED = 4401;
  const BACKOFF = [1000, 2000, 5000, 10000, 20000];
  const listeners = new Map();
  let socket = null;
  let attempt = 0;
  let started = false;

  AV.on = function (event, fn) {
    if (!listeners.has(event)) listeners.set(event, new Set());
    listeners.get(event).add(fn);
    return () => listeners.get(event).delete(fn);
  };

  function dispatch(event, data) {
    (listeners.get(event) || []).forEach((fn) => {
      try { fn(data); } catch (e) { console.error("listener failed for " + event, e); }
    });
  }

  function connect() {
    socket = new WebSocket("ws://" + location.host + "/ws");
    socket.onmessage = (message) => {
      try {
        const payload = JSON.parse(message.data);
        dispatch(payload.e, payload.d);
      } catch (e) {
        console.error("bad live payload", e);
      }
    };
    socket.onopen = () => { attempt = 0; dispatch("link", true); };
    socket.onclose = (event) => {
      dispatch("link", false);
      if (event.code === SESSION_EXPIRED) {
        dispatch("expired", true);
        return;
      }
      setTimeout(connect, BACKOFF[Math.min(attempt++, BACKOFF.length - 1)]);
    };
  }

  AV.live = { start() { if (!started) { started = true; connect(); } } };
})();
