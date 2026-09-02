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

async function notify(title, message) {
  try {
    await browser.notifications.create({
      type: "basic",
      iconUrl: "icons/icon-128.png",
      title,
      message
    });
  } catch (error) {
    console.error("FileApex extension notification:", error);
  }
}

async function showBadgeOk() {
  await browser.action.setBadgeBackgroundColor({ color: "#0f766e" });
  await browser.action.setBadgeText({ color: "#ffffff" });
  await browser.action.setBadgeText({ text: "✓" });
  setTimeout(() => {
    browser.action.setBadgeText({ text: "" });
  }, 2500);
}

async function showBadgeError() {
  await browser.action.setBadgeBackgroundColor({ color: "#b91c1c" });
  await browser.action.setBadgeText({ color: "#ffffff" });
  await browser.action.setBadgeText({ text: "!" });
  setTimeout(() => {
    browser.action.setBadgeText({ text: "" });
  }, 3500);
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

browser.action.onClicked.addListener(async (tab) => {
  try {
    if (!canSendTab(tab)) {
      throw new Error("FileApex can only send regular web pages (http/https).");
    }
    await sendToBulletinBoard(tab);
    await showBadgeOk();
    await notify("FileApex", "Sent to Bulletin Board.");
  } catch (error) {
    console.error("FileApex extension:", error);
    await showBadgeError();
    const message = String(error && error.message ? error.message : error);
    if (message.includes("Failed to fetch") || message.includes("NetworkError")) {
      await notify(
        "FileApex",
        "Could not reach FileApex. Open the FileApex app on this Mac, then try again."
      );
      return;
    }
    await notify("FileApex", message || "Send failed.");
  }
});
