// 记录 WebView 主框架的每一次导航和重定向，直到回到 physicsforums.com 且出现 xf_user Cookie，或超时
// 用法：node nav_logger.mjs [超时秒数，默认 600]   输出同时写入 output/nav_log.txt
import fs from "node:fs";
import path from "node:path";

const OUT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, "$1")), "output", "nav_log.txt");
const timeoutSec = Number(process.argv[2] ?? 600);
const lines = [];
const log = (s) => { const line = `${new Date().toISOString().slice(11, 19)} ${s}`; console.log(line); lines.push(line); fs.writeFileSync(OUT, lines.join("\n") + "\n"); };

const page = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((p) => p.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok) => (ws.onopen = ok));
let seq = 0;
const pending = new Map();
ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); return; }
    if (m.method === "Network.requestWillBeSent" && m.params.type === "Document") {
        const rr = m.params.redirectResponse;
        log(`${rr ? `  ↳ ${rr.status} ` : "REQ "}${m.params.request.method} ${m.params.request.url.slice(0, 140)}`);
    } else if (m.method === "Network.responseReceivedExtraInfo") {
        // 重定向被壳取消时不会有下一条 requestWillBeSent，只能从响应头里看它原本要去哪
        const h = m.params.headers || {};
        const loc = h.location ?? h.Location;
        if (loc) log(`  Location: ${loc.slice(0, 160)}`);
    } else if (m.method === "Network.loadingFailed" && m.params.type === "Document") {
        log(`FAIL ${m.params.errorText} ${m.params.canceled ? "(canceled，可能被壳转交外部)" : ""}`);
    } else if (m.method === "Page.frameNavigated" && !m.params.frame.parentId) {
        log(`NAV ${m.params.frame.url.slice(0, 140)}`);
    }
};
const send = (method, params = {}) => new Promise((ok) => { const id = ++seq; pending.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });

await send("Network.enable");
await send("Page.enable");
log(`开始记录，当前页面 ${page.url.slice(0, 100)}`);

const end = Date.now() + timeoutSec * 1000;
while (Date.now() < end) {
    await new Promise((r) => setTimeout(r, 3000));
    const { cookies } = await send("Network.getCookies", { urls: ["https://www.physicsforums.com/"] });
    if (cookies.some((c) => c.name === "xf_user")) {
        log("检测到 xf_user Cookie：PF 已登录");
        break;
    }
}
ws.close();
