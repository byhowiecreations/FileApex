const ext = globalThis.browser || globalThis.chrome;
const DEFAULT_PORT = 8080;

function sanitizePort(raw) {
  const num = Number.parseInt(String(raw == null ? "" : raw).trim(), 10);
  return Number.isInteger(num) && num >= 1 && num <= 65535 ? num : DEFAULT_PORT;
}

async function getServerPort() {
  try {
    if (ext.storage && ext.storage.local) {
      const data = await ext.storage.local.get("serverPort");
      return sanitizePort(data && data.serverPort);
    }
  } catch (_) {}
  return DEFAULT_PORT;
}

async function setServerPort(raw) {
  const num = Number.parseInt(String(raw == null ? "" : raw).trim(), 10);
  if (!Number.isInteger(num) || num < 1 || num > 65535) {
    throw new Error("Port must be between 1 and 65535.");
  }
  if (ext.storage && ext.storage.local) {
    await ext.storage.local.set({ serverPort: num });
  }
  return num;
}

async function getServerBaseUrl() {
  const port = await getServerPort();
  return `http://127.0.0.1:${port}`;
}

const MENU_ROOT = "fileapex-root";
const MENU_OPEN_PARENT = "fileapex-open-parent";
const MENU_COPY_PARENT = "fileapex-copy-parent";
const MENU_POST_BULLETIN = "fileapex-post-bulletin";
const MENU_PASTE = "fileapex-paste";
const OPEN_PREFIX = "fileapex-open:";
const COPY_PREFIX = "fileapex-copy:";

function canSendUrl(url) {
  return typeof url === "string" && (url.startsWith("http://") || url.startsWith("https://"));
}

async function notify(title, message) {
  try {
    await ext.notifications.create({
      type: "basic",
      iconUrl: "icons/icon-128.png",
      title,
      message
    });
  } catch (error) {
    console.error("FileApex extension notification:", error);
  }
}

async function flashBadge(ok) {
  try {
    await ext.action.setBadgeBackgroundColor({ color: ok ? "#0f766e" : "#b91c1c" });
    await ext.action.setBadgeText({ text: ok ? "OK" : "!" });
    setTimeout(() => {
      ext.action.setBadgeText({ text: "" });
    }, ok ? 2200 : 3200);
  } catch (_) {}
}

async function fetchDevices() {
  const baseUrl = await getServerBaseUrl();
  const response = await fetch(`${baseUrl}/api/v1/web/devices`);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  const list = await response.json();
  return Array.isArray(list) ? list : [];
}

async function postJson(path, body) {
  const baseUrl = await getServerBaseUrl();
  const response = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  return response.json().catch(() => ({}));
}

async function sendToDevice(targetDeviceId, text) {
  if (!targetDeviceId || !text) {
    throw new Error("Target device and text are required.");
  }
  return postJson("/api/v1/web/send-clipboard", { targetDeviceId, text });
}

async function postToBulletinBoard({ url = "", title = "", text = "" }) {
  if (!url && !title && !text) {
    throw new Error("Nothing selected to post.");
  }
  return postJson("/api/v1/web/post-bulletin", { url, title, text });
}

async function pasteClipboardIntoTab(tabId) {
  await ext.scripting.executeScript({
    target: { tabId },
    func: async () => {
      const text = await navigator.clipboard.readText();
      if (!text) return;
      const el = document.activeElement;
      if (!el) return;
      if ("value" in el && typeof el.selectionStart === "number") {
        const start = el.selectionStart;
        const end = el.selectionEnd || start;
        el.setRangeText(text, start, end, "end");
        el.dispatchEvent(new Event("input", { bubbles: true }));
      } else if (el.isContentEditable) {
        document.execCommand("insertText", false, text);
      }
    }
  });
}

async function rebuildContextMenus() {
  try {
    await ext.contextMenus.removeAll();
    ext.contextMenus.create({
      id: MENU_ROOT,
      title: "FileApex",
      contexts: ["page", "link", "selection", "editable"]
    });
    ext.contextMenus.create({
      id: MENU_OPEN_PARENT,
      parentId: MENU_ROOT,
      title: "Open on...",
      contexts: ["page", "link"]
    });
    ext.contextMenus.create({
      id: MENU_COPY_PARENT,
      parentId: MENU_ROOT,
      title: "Copy to clipboard on...",
      contexts: ["page", "link", "selection"]
    });
    ext.contextMenus.create({
      id: MENU_POST_BULLETIN,
      parentId: MENU_ROOT,
      title: "Post to Bulletin Board",
      contexts: ["page", "link", "selection"]
    });
    ext.contextMenus.create({
      id: MENU_PASTE,
      parentId: MENU_ROOT,
      title: "Paste",
      contexts: ["editable"]
    });

    const devices = await fetchDevices().catch(() => []);
    if (devices.length === 0) {
      ext.contextMenus.create({
        id: "fileapex-no-devices-open",
        parentId: MENU_OPEN_PARENT,
        title: "Open FileApex to list devices",
        enabled: false,
        contexts: ["page", "link"]
      });
      ext.contextMenus.create({
        id: "fileapex-no-devices-copy",
        parentId: MENU_COPY_PARENT,
        title: "Open FileApex to list devices",
        enabled: false,
        contexts: ["page", "link", "selection"]
      });
      return;
    }
    for (const dev of devices) {
      const label = dev.deviceName || dev.deviceId;
      ext.contextMenus.create({
        id: `${OPEN_PREFIX}${dev.deviceId}`,
        parentId: MENU_OPEN_PARENT,
        title: label,
        contexts: ["page", "link"]
      });
      ext.contextMenus.create({
        id: `${COPY_PREFIX}${dev.deviceId}`,
        parentId: MENU_COPY_PARENT,
        title: label,
        contexts: ["page", "link", "selection"]
      });
    }
  } catch (error) {
    console.error("FileApex context menu build:", error);
  }
}

ext.runtime.onInstalled.addListener(() => { rebuildContextMenus(); });
ext.runtime.onStartup.addListener(() => { rebuildContextMenus(); });

ext.contextMenus.onClicked.addListener(async (info, tab) => {
  try {
    const id = String(info.menuItemId || "");
    if (id.startsWith(OPEN_PREFIX)) {
      const deviceId = id.slice(OPEN_PREFIX.length);
      const targetUrl = info.linkUrl || info.pageUrl || (tab && tab.url) || "";
      if (!canSendUrl(targetUrl)) throw new Error("Only http/https URLs can be opened.");
      await sendToDevice(deviceId, targetUrl);
      await flashBadge(true);
      await notify("FileApex", "Sent link to device.");
    } else if (id.startsWith(COPY_PREFIX)) {
      const deviceId = id.slice(COPY_PREFIX.length);
      const text = info.selectionText || info.linkUrl || info.pageUrl || (tab && tab.url) || "";
      await sendToDevice(deviceId, text);
      await flashBadge(true);
      await notify("FileApex", "Copied to device clipboard.");
    } else if (id === MENU_POST_BULLETIN) {
      await postToBulletinBoard({
        url: info.linkUrl || (tab && tab.url) || "",
        title: (tab && tab.title) || "",
        text: info.selectionText || ""
      });
      await flashBadge(true);
      await notify("FileApex", "Posted to Bulletin Board.");
    } else if (id === MENU_PASTE && tab && tab.id != null) {
      await pasteClipboardIntoTab(tab.id);
    }
  } catch (error) {
    await flashBadge(false);
    const msg = String(error && error.message ? error.message : error);
    await notify("FileApex", msg.includes("Failed to fetch") ? "Open the FileApex app first." : msg);
  }
});

ext.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  (async () => {
    try {
      if (message && message.type === "getDevices") {
        const port = await getServerPort();
        const devices = await fetchDevices();
        rebuildContextMenus();
        sendResponse({ ok: true, devices, port });
      } else if (message && message.type === "getConfig") {
        const port = await getServerPort();
        sendResponse({ ok: true, port });
      } else if (message && message.type === "setPort") {
        const port = await setServerPort(message.port);
        rebuildContextMenus();
        sendResponse({ ok: true, port });
      } else if (message && message.type === "sendToDevice") {
        const result = await sendToDevice(message.deviceId, message.text);
        await flashBadge(true);
        sendResponse({ ok: true, result });
      } else if (message && message.type === "postBulletin") {
        await postToBulletinBoard(message.payload || {});
        await flashBadge(true);
        sendResponse({ ok: true });
      } else {
        sendResponse({ ok: false, error: "unknown_action" });
      }
    } catch (error) {
      await flashBadge(false);
      sendResponse({ ok: false, error: String(error && error.message ? error.message : error) });
    }
  })();
  return true;
});
