// Defines window.Native in every page. The Android app only answers pages whose
// origin matches the saved launch URL.
(function () {
  if (window.top !== window) return;
  var ORIGIN = location.origin;

  var METHODS = {
    app: ["info", "setUrl", "reload", "openSettings", "openUrl", "pauseKiosk"],
    apps: ["list", "icon", "launch"],
    display: ["setBrightness", "keepAwake", "setOrientation"],
    audio: ["getVolume", "setVolume"],
    power: ["setMode", "sleep", "wake", "reboot", "shutdown"],
    keys: ["setConsume"],
    sensors: ["list", "start", "stop"],
    fs: ["roots", "list", "stat", "read", "write", "mkdir", "delete"],
    perms: ["status", "requestAll"],
    root: ["available", "exec"],
    battery: ["get"],
    clipboard: ["read", "write"],
    device: ["vibrate"]
  };

  var nextId = 1;
  var pending = {};
  var handlers = {};
  var port = null;

  function connect() {
    try {
      port = browser.runtime.connect({ name: "page" });
      port.onMessage.addListener(onNative);
      port.onDisconnect.addListener(function () { port = null; setTimeout(connect, 500); });
    } catch (e) { port = null; setTimeout(connect, 500); }
  }

  function post(msg) {
    msg.origin = ORIGIN;
    if (!port) connect();
    try { port.postMessage(msg); return true; } catch (e) { return false; }
  }

  function clone(v) { try { return JSON.parse(JSON.stringify(v)); } catch (e) { return null; } }
  function toPage(v) { return v === undefined ? undefined : cloneInto(v, window, { cloneFunctions: false }); }

  function onNative(m) {
    if (!m) return;
    if (typeof m.id === "number" && pending[m.id]) {
      var p = pending[m.id];
      delete pending[m.id];
      if (m.ok) p.res(toPage(m.result)); else p.rej(String(m.error || "error"));
      return;
    }
    if (m.event) {
      var data = toPage(m.data === undefined ? null : m.data);
      var list = handlers[m.event] || [];
      for (var i = 0; i < list.length; i++) { try { list[i](data); } catch (e) {} }
      var all = handlers["*"] || [];
      for (var j = 0; j < all.length; j++) { try { all[j](cloneInto({ event: m.event, data: m.data }, window)); } catch (e) {} }
      try {
        window.dispatchEvent(new window.CustomEvent("native:" + m.event, { detail: data }));
      } catch (e) {}
    }
  }

  function call(method, args) {
    return new window.Promise(exportFunction(function (res, rej) {
      var id = nextId++;
      pending[id] = { res: res, rej: rej };
      var ok = post({ id: id, method: method, args: clone(args) || [] });
      if (!ok) { delete pending[id]; rej("bridge not connected"); }
    }, window));
  }

  var Native = new window.Object();
  exportFunction(function (method) {
    return call(String(method), Array.prototype.slice.call(arguments, 1));
  }, Native, { defineAs: "call" });
  exportFunction(function (name, fn) {
    (handlers[name] = handlers[name] || []).push(fn);
  }, Native, { defineAs: "on" });
  exportFunction(function (name, fn) {
    var l = handlers[name]; if (!l) return;
    var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1);
  }, Native, { defineAs: "off" });

  Object.keys(METHODS).forEach(function (ns) {
    var obj = new window.Object();
    METHODS[ns].forEach(function (name) {
      var full = ns + "." + name;
      exportFunction(function () {
        return call(full, Array.prototype.slice.call(arguments));
      }, obj, { defineAs: name });
    });
    Native.wrappedJSObject[ns] = obj.wrappedJSObject;
  });

  window.wrappedJSObject.Native = Native;
  connect();
  post({ id: 0, method: "hello" });
})();
