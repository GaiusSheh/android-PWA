// 在当前 WebView 页面里触发一次跳转，可选是否带用户手势；配合 adb 截图看是否弹出确认框
// 用法：node trigger_nav.mjs <地址> [gesture|nogesture]
const [url, mode = "nogesture"] = process.argv.slice(2);
if (!url) throw new Error("用法：node trigger_nav.mjs <地址> [gesture|nogesture]");
const page = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((p) => p.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok) => (ws.onopen = ok));
ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id === 1) { console.log(`已触发（${mode}），当前页面 ${page.url.slice(0, 80)}`); ws.close(); } };
const expression = mode === "gesture"
    ? `location.href = ${JSON.stringify(url)}; true`
    : `setTimeout(() => { location.href = ${JSON.stringify(url)}; }, 0); true`;
ws.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression, returnByValue: true, userGesture: mode === "gesture" } }));
