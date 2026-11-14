const ext = globalThis.browser || globalThis.chrome;
const SERVER_BASE_URL = "http://127.0.0.1:8080";

function tabPayload(tab) {
  const url = (tab && tab.url) || "";
  const title = (tab && tab.title) || "";
  return { url, title };
}

function canSendTab(tab) {
  const url = (tab && tab.url) || "";
  return url.startsWith("http://") || url.startsWith("https://");
}

async function resolveActiveTab(clickedTab) {
  if (clickedTab && clickedTab.url) {
    return clickedTab;
  }
  const tabs = await ext.tabs.query({ active: true, currentWindow: true });
  return tabs[0] || clickedTab;
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
    await ext.action.setBadgeText({ color: "#ffffff" });
    await ext.action.setBadgeText({ text: ok ? "✓" : "!" });
    setTimeout(() => {
      ext.action.setBadgeText({ text: "" });
    }, ok ? 2500 : 3500);
  } catch (_) {}
}

async function postJson(path, body) {
  const response = await fetch(`${SERVER_BASE_URL}${path}`, {
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

async function sendToBulletinBoard(tab) {
  const payload = tabPayload(tab);
  if (!payload.url && !payload.title) {
    throw new Error("This page has no URL or title to send.");
  }
  await postJson("/api/v1/web/post-bulletin", payload);
}

ext.action.onClicked.addListener(async (clickedTab) => {
  try {
    const tab = await resolveActiveTab(clickedTab);
    if (!canSendTab(tab)) {
      throw new Error("FileApex can only send regular web pages (http/https).");
    }
    await sendToBulletinBoard(tab);
    await flashBadge(true);
    await notify("FileApex", "Sent to Bulletin Board.");
  } catch (error) {
    console.error("FileApex extension:", error);
    await flashBadge(false);
    const message = String(error && error.message ? error.message : error);
    if (message.includes("Failed to fetch") || message.includes("NetworkError")) {
      await notify(
        "FileApex",
        "Could not reach FileApex. Open the FileApex app first, then try again."
      );
      return;
    }
    await notify("FileApex", message || "Send failed.");
  }
});
