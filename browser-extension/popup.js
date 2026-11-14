const ext = globalThis.browser || globalThis.chrome;

const deviceSelect = document.getElementById("device");
const statusEl = document.getElementById("status");
const btnBulletin = document.getElementById("postBulletin");
const btnOpen = document.getElementById("openOnDevice");
const btnCopy = document.getElementById("copyToDevice");
const portInput = document.getElementById("serverPort");
const btnSavePort = document.getElementById("savePort");

function setStatus(text, isError = false) {
  statusEl.textContent = text;
  statusEl.style.color = isError ? "#fca5a5" : "#5eead4";
}

function canSendTab(tab) {
  const url = (tab && tab.url) || "";
  return url.startsWith("http://") || url.startsWith("https://");
}

async function activeTab() {
  const tabs = await ext.tabs.query({ active: true, currentWindow: true });
  return tabs[0] || null;
}

async function init() {
  try {
    const cfg = await ext.runtime.sendMessage({ type: "getConfig" }).catch(() => null);
    if (cfg && cfg.port && portInput) {
      portInput.value = String(cfg.port);
    }
    const resp = await ext.runtime.sendMessage({ type: "getDevices" });
    if (!resp || !resp.ok) {
      throw new Error((resp && resp.error) || "offline");
    }
    const devices = resp.devices || [];
    btnBulletin.disabled = false;
    btnOpen.disabled = false;
    btnCopy.disabled = false;
    deviceSelect.innerHTML = "";
    if (devices.length === 0) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No online/paired devices";
      deviceSelect.appendChild(opt);
      btnOpen.disabled = true;
      btnCopy.disabled = true;
    } else {
      for (const dev of devices) {
        const opt = document.createElement("option");
        opt.value = dev.deviceId;
        opt.textContent = dev.deviceName || dev.deviceId;
        deviceSelect.appendChild(opt);
      }
    }
    setStatus(`Connected on 127.0.0.1:${resp.port || 8080}`);
  } catch (_) {
    setStatus("Open the FileApex desktop app first.", true);
    btnBulletin.disabled = true;
    btnOpen.disabled = true;
    btnCopy.disabled = true;
  }
}

btnBulletin.addEventListener("click", async () => {
  const tab = await activeTab();
  if (!canSendTab(tab)) {
    setStatus("Only http/https pages can be sent.", true);
    return;
  }
  const resp = await ext.runtime.sendMessage({
    type: "postBulletin",
    payload: { url: tab.url, title: tab.title || "" }
  });
  if (resp && resp.ok) {
    setStatus("Posted to Bulletin Board.");
  } else {
    setStatus((resp && resp.error) || "Post failed.", true);
  }
});

async function sendTabUrl(successLabel) {
  const tab = await activeTab();
  const deviceId = deviceSelect.value;
  if (!deviceId) {
    setStatus("Select a target device.", true);
    return;
  }
  if (!canSendTab(tab)) {
    setStatus("Only http/https pages can be sent.", true);
    return;
  }
  const resp = await ext.runtime.sendMessage({
    type: "sendToDevice",
    deviceId,
    text: tab.url
  });
  if (resp && resp.ok) {
    setStatus(successLabel);
  } else {
    setStatus((resp && resp.error) || "Send failed.", true);
  }
}

btnOpen.addEventListener("click", () => sendTabUrl("Opened on device."));
btnCopy.addEventListener("click", () => sendTabUrl("Copied to device clipboard."));
if (btnSavePort && portInput) {
  btnSavePort.addEventListener("click", async () => {
    const resp = await ext.runtime.sendMessage({ type: "setPort", port: portInput.value });
    if (resp && resp.ok) {
      portInput.value = String(resp.port);
      setStatus(`Saved port ${resp.port}. Refreshing...`);
      await init();
    } else {
      setStatus((resp && resp.error) || "Invalid port.", true);
    }
  });
}

init();
