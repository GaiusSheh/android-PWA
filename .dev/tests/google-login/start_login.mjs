// 绕开 PF 自身的 CSP bug（form-action 'self' 拦下表单提交后跳到 accounts.google.com 的 303），
// 用 WebView 自己的 PF 会话发起 Google 登录，之后由人在设备上手动完成登录。
// 做法：WebView 打开确认页 → 通过 CDP 读出 _xfToken 和 PF Cookie → PC 端带同一份 Cookie 提交表单
//       → 拿到 Location（state 绑定在 WebView 的 PF 会话上）→ 让 WebView 打开它。
// 前提同 cdp_probe.mjs（adb forward tcp:9333 已建立）
const CONNECT_URL = "https://www.physicsforums.com/login/register/connected-accounts/google/?setup=1";

const page = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((p) => p.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok) => (ws.onopen = ok));
let seq = 0;
const pending = new Map();
ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
const send = async (method, params = {}) => {
    const m = await new Promise((ok) => { const id = ++seq; pending.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });
    if (m.error) throw new Error(`${method} 失败：${JSON.stringify(m.error)}`);
    return m.result;
};
const evaluate = async (expr) => (await send("Runtime.evaluate", { expression: expr, returnByValue: true })).result.value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await send("Page.navigate", { url: CONNECT_URL });
await sleep(6000);
const token = await evaluate(`document.querySelector('form[action*="connected-accounts/google"] input[name=_xfToken]')?.value`);
if (!token) throw new Error("确认页里没有找到 _xfToken（页面没加载完或已登录？）");
const ua = await evaluate("navigator.userAgent");
const { cookies } = await send("Network.getCookies", { urls: ["https://www.physicsforums.com/"] });
const cookie = cookies.map((c) => `${c.name}=${c.value}`).join("; ");
console.log(`PF Cookie：${cookies.map((c) => c.name).join(", ")}`);

const r = await fetch(CONNECT_URL, {
    method: "POST",
    redirect: "manual",
    headers: { "User-Agent": ua, Cookie: cookie, Referer: CONNECT_URL, "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ _xfToken: token }),
});
const loc = r.headers.get("location");
if (!loc?.includes("accounts.google.com")) throw new Error(`期望跳到 accounts.google.com，实际 ${r.status} ${loc}`);
// 服务端若在这一步更新了 Cookie，同步回 WebView，保证回调时会话一致
for (const sc of r.headers.getSetCookie()) {
    const [nv] = sc.split(";");
    const i = nv.indexOf("=");
    await send("Network.setCookie", { name: nv.slice(0, i), value: nv.slice(i + 1), url: "https://www.physicsforums.com/", secure: true });
    console.log(`同步 Cookie：${nv.slice(0, i)}`);
}
await send("Page.navigate", { url: loc });
console.log(`WebView 已打开 Google 授权页（state=${new URL(loc).searchParams.get("state")}），请在设备上完成登录`);
ws.close();
