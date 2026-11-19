// Relays between the page (content script ports) and the Android app (native port).
const pagePorts = new Set();
let nativePort = null;

function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("browser");
    nativePort.onMessage.addListener((msg) => {
      pagePorts.forEach((p) => { try { p.postMessage(msg); } catch (e) {} });
    });
    nativePort.onDisconnect.addListener(() => {
      nativePort = null;
      setTimeout(connectNative, 500);
    });
  } catch (e) {
    nativePort = null;
    setTimeout(connectNative, 500);
  }
}

browser.runtime.onConnect.addListener((p) => {
  pagePorts.add(p);
  p.onDisconnect.addListener(() => pagePorts.delete(p));
  p.onMessage.addListener((msg) => {
    if (!nativePort) connectNative();
    try { nativePort && nativePort.postMessage(msg); } catch (e) {}
  });
});

connectNative();
