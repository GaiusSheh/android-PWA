// Phase 2 回归：登录入口接管、流程结束、普通外链、弹窗分流（不需要账号，可在 WSA 上跑）
// 前提：pftest 的 allowedHosts 只有 physicsforums.com；adb forward tcp:9333 已指向该 App 的 WebView
// 用法：node regression.mjs <adb 设备序列号>
// 「交给外部」通过 logcat 里的 ActivityTaskManager START ... VIEW 判断
import { execFileSync } from "node:child_process";

const SERIAL = process.argv[2];
if (!SERIAL) throw new Error("用法：node regression.mjs <adb 设备序列号>");
const PF = "https://www.physicsforums.com/";
const CONNECT_URL = `${PF}login/register/connected-accounts/google/?setup=1`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...args) => execFileSync("adb", ["-s", SERIAL, ...args], { encoding: "utf8", maxBuffer: 64 << 20 });

class Cdp {
    static async attach(match) {
        const pages = await (await fetch("http://127.0.0.1:9333/json")).json();
        const page = pages.find((p) => p.type === "page" && match(p));
        if (!page) return null;
        const c = new Cdp();
        c.ws = new WebSocket(page.webSocketDebuggerUrl);
        c.seq = 0;
        c.pending = new Map();
        c.ws.onmessage = (ev) => {
            const m = JSON.parse(ev.data);
            if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
        };
        await new Promise((ok) => (c.ws.onopen = ok));
        return c;
    }
    send(method, params = {}) {
        return new Promise((ok) => { const id = ++this.seq; this.pending.set(id, ok); this.ws.send(JSON.stringify({ id, method, params })); });
    }
    async eval(expression, userGesture = false) {
        const m = await this.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true, userGesture });
        return m.result?.result?.value;
    }
    close() { this.ws.close(); }
}

const pageList = async () => (await (await fetch("http://127.0.0.1:9333/json")).json()).filter((p) => p.type === "page");
const viewIntents = () => adb("logcat", "-d", "-s", "ActivityTaskManager:I").split("\n").filter((l) => /START .*VIEW/.test(l));

const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok }); console.log(`${ok ? "✓" : "✗"} ${name}${detail ? `  (${detail})` : ""}`); };

async function waitHref(main, pred, ms) {
    const end = Date.now() + ms;
    let href = "";
    while (Date.now() < end) {
        href = (await main.eval("location.href").catch(() => "")) ?? "";
        if (pred(href)) return href;
        await sleep(500);
    }
    return href;
}

let main = await Cdp.attach(() => true);
await main.send("Network.clearBrowserCookies");

// 1. 登录入口接管：PF 确认页点「Continue」，表单 POST 后 303 到 accounts.google.com（会被 PF 自己的 CSP 拦下）
await main.send("Page.navigate", { url: CONNECT_URL });
await waitHref(main, (u) => u.includes("connected-accounts/google"), 15000);
await sleep(4000);
await main.eval(`document.querySelector('form[action*="connected-accounts/google"] button[type=submit]').click()`, true);
let href = await waitHref(main, (u) => u.includes("accounts.google.com"), 20000);
check("1 PF Continue → App 内打开 accounts.google.com", href.includes("accounts.google.com"), href.slice(0, 80));

// 2. 回到网站后登录流程结束，普通 YouTube 链接交给外部
await main.send("Page.navigate", { url: PF });
await waitHref(main, (u) => u.startsWith(PF), 15000);
await sleep(3000);
adb("logcat", "-c");
await main.eval(`location.href = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'`, true);
await sleep(4000);
href = await main.eval("location.href");
let intents = viewIntents();
check("2 回到 PF 后 YouTube 视频链接不在 App 内打开", href.startsWith(PF), href.slice(0, 60));
check("2 且交给了外部", intents.some((l) => l.includes("youtube")), `${intents.length} 条 VIEW`);

// 3a. 用户点击打开登录弹窗：显示为弹窗、opener 可用、主页面不动
const oauth = "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&redirect_uri=https%3A%2F%2Fwww.physicsforums.com%2F&response_type=code&scope=email";
await main.eval(`window.open(${JSON.stringify(oauth)}, 'login', 'width=500,height=600'); true`, true);
await sleep(6000);
let pages = await pageList();
const popupPage = pages.find((p) => p.url.includes("accounts.google.com"));
href = await main.eval("location.href");
check("3a 登录弹窗出现（独立的 WebView）", !!popupPage, popupPage?.url.slice(0, 60));
check("3a 主页面不变", href.startsWith(PF), href.slice(0, 60));
if (popupPage) {
    const pop = await Cdp.attach((p) => p.id === popupPage.id);
    check("3a 弹窗里 window.opener 可用", await pop.eval("!!window.opener"));
    await pop.eval("window.close()");
    pop.close();
    await sleep(2000);
    pages = await pageList();
    check("3a window.close() 关闭弹窗", !pages.some((p) => p.id === popupPage.id), `剩余 ${pages.length} 个页面`);
}

// 3b. 用户点击的站内新窗口：在主 WebView 打开
await main.eval(`window.open('${PF}help/', '_blank'); true`, true);
href = await waitHref(main, (u) => u.includes("/help/"), 10000);
check("3b 站内 _blank → 主 WebView 打开", href.includes("/help/"), href.slice(0, 60));
await sleep(2000);

// 3c. 非用户点击的站外弹窗：丢弃，不打开任何东西
adb("logcat", "-c");
const before = href;
await main.eval(`window.open('https://example.com/', '_blank'); true`, false);
await sleep(7000);
href = await main.eval("location.href");
intents = viewIntents();
pages = await pageList();
check("3c 非点击弹窗被丢弃：主页面不变", href === before, href.slice(0, 60));
check("3c 且没有交给外部", !intents.some((l) => l.includes("example.com")), `${intents.length} 条 VIEW`);
check("3c 且没有残留页面", pages.length === 1, `${pages.length} 个页面`);

// 3d. 用户点击的站外新窗口：交给外部
adb("logcat", "-c");
await main.eval(`window.open('https://example.com/', '_blank'); true`, true);
await sleep(5000);
href = await main.eval("location.href");
intents = viewIntents();
check("3d 站外 _blank → 交给外部", intents.some((l) => l.includes("example.com")), `${intents.length} 条 VIEW`);
check("3d 主页面不变", href === before, href.slice(0, 60));

main.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} 通过`);
process.exit(failed ? 1 : 0);
