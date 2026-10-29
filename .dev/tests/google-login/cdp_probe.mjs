// 探路：在真实 WebView 里用不同 UA 走 Physics Forums 的 Google 登录，看 Google 是否拦截
// 前提：debug 版壳已在设备上运行，且 adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>
// 用法：node cdp_probe.mjs [变体名...]   （不写则全部跑一遍）
// 输出：output/<变体>.png 截图、output/<变体>.json（最终地址、页面文字、发给 accounts.google.com 的请求头）
import fs from "node:fs";
import path from "node:path";

const OUT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, "$1")), "output");
const CONNECT_URL = "https://www.physicsforums.com/login/register/connected-accounts/google/?setup=1";
// 不存在的测试邮箱：只用来触发「提交邮箱」后的第二道检查，不涉及真实账号
const PROBE_EMAIL = "webshell.probe.nonexistent.20261029@gmail.com";

const pages = await (await fetch("http://127.0.0.1:9333/json")).json();
const page = pages.find((p) => p.type === "page");
if (!page) throw new Error(`没有找到 WebView 页面，/json 返回：${JSON.stringify(pages)}`);

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
let seq = 0;
const pending = new Map();
const listeners = [];
ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
        const { ok, fail } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? fail(new Error(JSON.stringify(msg.error))) : ok(msg.result);
    } else if (msg.method) {
        listeners.forEach((fn) => fn(msg));
    }
};
const send = (method, params = {}) =>
    new Promise((ok, fail) => {
        const id = ++seq;
        pending.set(id, { ok, fail });
        ws.send(JSON.stringify({ id, method, params }));
    });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const evaluate = async (expr) => (await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true })).result.value;

const defaultUA = await evaluate("navigator.userAgent");
const chromeVer = defaultUA.match(/Chrome\/(\d+)/)[1];
const noWv = defaultUA.replace("; wv", "").replace("Version/4.0 ", "");
const chromeBrands = [
    { brand: "Google Chrome", version: chromeVer },
    { brand: "Chromium", version: chromeVer },
    { brand: "Not=A?Brand", version: "99" },
];

const VARIANTS = {
    // 不覆盖：WebView 原样
    default: null,
    // 只改 UA 字符串：去掉 "; wv" 和 "Version/4.0"
    ua_no_wv: { userAgent: noWv },
    // 再把 Client Hints 的品牌伪装成 Chrome（WebView 默认自报 "Android WebView"）
    ua_no_wv_brands: {
        userAgent: noWv,
        userAgentMetadata: {
            brands: chromeBrands,
            fullVersionList: chromeBrands,
            platform: "Android", platformVersion: "13.0.0", architecture: "", model: "", mobile: true,
        },
    },
};

await send("Page.enable");
await send("Network.enable");
await send("Runtime.enable");

const googleHeaders = [];
listeners.push((msg) => {
    if (msg.method === "Network.requestWillBeSentExtraInfo") {
        const h = msg.params.headers || {};
        const url = (h[":authority"] || "") + (h[":path"] || "");
        if (url.startsWith("accounts.google.com")) googleHeaders.push({ url: url.slice(0, 120), headers: h });
    }
});

async function fetchOAuthUrl() {
    const ua = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
    const r1 = await fetch(CONNECT_URL, { headers: { "User-Agent": ua } });
    const cookie = r1.headers.getSetCookie().map((c) => c.split(";")[0]).join("; ");
    const token = (await r1.text()).match(/name="_xfToken" value="([^"]+)"/)?.[1];
    if (!token) throw new Error("PF 确认页里没有找到 _xfToken");
    const r2 = await fetch(CONNECT_URL, {
        method: "POST",
        redirect: "manual",
        headers: { "User-Agent": ua, Cookie: cookie, Referer: CONNECT_URL, "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ _xfToken: token }),
    });
    const loc = r2.headers.get("location");
    if (!loc?.includes("accounts.google.com")) throw new Error(`期望跳到 accounts.google.com，实际 ${r2.status} ${loc}`);
    return loc;
}

async function waitForUrl(pred, timeoutMs) {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
        const href = await evaluate("location.href").catch(() => "");
        if (pred(href)) return href;
        await sleep(500);
    }
    return await evaluate("location.href").catch(() => "");
}

async function snapshot(name, extra) {
    const shot = await send("Page.captureScreenshot", { format: "png" });
    fs.writeFileSync(path.join(OUT, `${name}.png`), Buffer.from(shot.data, "base64"));
    return {
        ...extra,
        href: await evaluate("location.href"),
        title: await evaluate("document.title"),
        text: (await evaluate("document.body ? document.body.innerText : ''")).slice(0, 1500),
    };
}

fs.mkdirSync(OUT, { recursive: true });
const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(VARIANTS);

for (const name of wanted) {
    const override = VARIANTS[name];
    if (override === undefined) throw new Error(`未知变体 ${name}，可选：${Object.keys(VARIANTS).join(", ")}`);
    googleHeaders.length = 0;

    await send("Network.clearBrowserCookies");
    await send("Network.setUserAgentOverride", override ?? { userAgent: defaultUA });

    // 1. 在 PC 端走一遍 PF 的确认表单，拿到真实的 Google 授权地址，再让 WebView 直接打开。
    //    （在 WebView 里原生 submit 会被 PF 的 CSP form-action 'self' 拦下跨域重定向；
    //     这里只关心 Google 对 WebView 的反应，state 和 PF 会话对不上不影响判断）
    const oauthUrl = await fetchOAuthUrl();
    await send("Page.navigate", { url: oauthUrl });

    // 2. 等跳到 accounts.google.com 并渲染完
    const googleUrl = await waitForUrl((u) => u.includes("accounts.google.com"), 20000);
    await sleep(5000);
    const step1 = await snapshot(`${name}-1-identifier`, {
        jsUserAgent: await evaluate("navigator.userAgent"),
        jsBrands: await evaluate("JSON.stringify(navigator.userAgentData && navigator.userAgentData.brands)"),
    });

    // 3. 填入不存在的测试邮箱并点「下一步」，触发第二道检查
    let step2 = null;
    if (googleUrl.includes("accounts.google.com") && !/disallowed_useragent/.test(step1.text + step1.href)) {
        const typed = await evaluate(`(() => {
            const el = document.querySelector('#identifierId, input[name=identifier], input[type=email]');
            if (!el) return false;
            el.focus();
            return true;
        })()`);
        console.log(`  [${name}] 找到邮箱输入框：${typed}`);
        if (typed) {
            await send("Input.insertText", { text: PROBE_EMAIL });
            await sleep(500);
            await evaluate(`(() => {
                const btn = [...document.querySelectorAll('button')].find(b => /next|下一步/i.test(b.innerText));
                if (btn) btn.click(); else document.querySelector('#identifierId').form?.requestSubmit();
            })()`);
            await sleep(7000);
            step2 = await snapshot(`${name}-2-after-email`, {});
        }
    }

    const result = { variant: name, override, step1, step2, googleRequestHeaders: googleHeaders.slice(0, 3) };
    fs.writeFileSync(path.join(OUT, `${name}.json`), JSON.stringify(result, null, 2));
    const brief = (s) => (s ? `${s.href.slice(0, 70)} | ${s.text.replace(/\s+/g, " ").slice(0, 160)}` : "-");
    console.log(`== ${name}\n  step1: ${brief(step1)}\n  step2: ${brief(step2)}`);
}

ws.close();
