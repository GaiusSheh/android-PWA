// 在 WebView 里打开 PF 的「Continue with Google」确认页，点确认按钮，记录控制台 / CSP 报错和导航
// 前提同 cdp_probe.mjs（adb forward tcp:9333 已建立）
const CONNECT_URL = "https://www.physicsforums.com/login/register/connected-accounts/google/?setup=1";

const page = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((p) => p.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok) => (ws.onopen = ok));
let seq = 0;
const pending = new Map();
ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); return; }
    switch (m.method) {
        case "Log.entryAdded": console.log(`[log:${m.params.entry.level}] ${m.params.entry.text}`); break;
        case "Runtime.consoleAPICalled": console.log(`[console] ${m.params.args.map((a) => a.value ?? a.description).join(" ")}`); break;
        case "Runtime.exceptionThrown": console.log(`[exception] ${m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text}`); break;
        case "Network.requestWillBeSent": {
            const r = m.params.request;
            if (r.method === "POST" || m.params.redirectResponse || /accounts\.google/.test(r.url))
                console.log(`[net] ${r.method} ${r.url.slice(0, 110)}${m.params.redirectResponse ? ` (redirect ${m.params.redirectResponse.status})` : ""}`);
            break;
        }
        case "Page.frameNavigated": if (!m.params.frame.parentId) console.log(`[nav] ${m.params.frame.url.slice(0, 110)}`); break;
    }
};
const send = (method, params = {}) => new Promise((ok) => { const id = ++seq; pending.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await send("Log.enable");
await send("Runtime.enable");
await send("Network.enable");
await send("Page.enable");

await send("Page.navigate", { url: CONNECT_URL });
await sleep(6000);
const r = await send("Runtime.evaluate", {
    returnByValue: true,
    expression: `(() => {
        const btn = document.querySelector('form[action*="connected-accounts/google"] button[type=submit]');
        if (!btn) return 'no-button';
        btn.click();
        return 'clicked';
    })()`,
});
console.log(`== 点击：${r.result.value}`);
await sleep(8000);
const href = await send("Runtime.evaluate", { expression: "location.href", returnByValue: true });
console.log(`== 最终地址：${href.result.value}`);
ws.close();
