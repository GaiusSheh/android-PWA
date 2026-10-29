// 判断某网站的「用 Google 登录」是弹窗模式还是整页跳转：
// 打开登录页 → 找到 Google 按钮（GIS 的 iframe 按钮或普通按钮）→ 用真实鼠标事件点击（带用户手势）
// → 看是出现了新页面（弹窗）还是主页面跳走
// 用法：node gis_probe.mjs <登录页地址> [按钮文字，默认 Google]
const [url, label = "Google"] = process.argv.slice(2);
if (!url) throw new Error("用法：node gis_probe.mjs <登录页地址> [按钮文字]");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pages = async () => (await (await fetch("http://127.0.0.1:9333/json")).json()).filter((p) => p.type === "page");

const page = (await pages())[0];
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok) => (ws.onopen = ok));
let seq = 0;
const pending = new Map();
ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); } };
const send = (method, params = {}) => new Promise((ok) => { const id = ++seq; pending.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });
const ev = async (expression) => (await send("Runtime.evaluate", { expression, returnByValue: true }))?.result?.value;

await send("Page.navigate", { url });
await sleep(12000);
const target = await ev(`(() => {
    const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 ? r : null; };
    // GIS 渲染的按钮是 accounts.google.com 的 iframe
    for (const f of document.querySelectorAll('iframe')) {
        if (/accounts\\.google\\.com\\/gsi\\/button/.test(f.src) && visible(f)) {
            const r = f.getBoundingClientRect();
            return { kind: 'gsi-iframe', x: r.x + r.width / 2, y: r.y + r.height / 2, src: f.src.slice(0, 120) };
        }
    }
    // 网站自己画的按钮
    const all = [...document.querySelectorAll('button, a, [role=button], div[tabindex]')];
    const el = all.find((e) => visible(e) && e.innerText && e.innerText.includes(${JSON.stringify(label)}) && e.innerText.length < 60);
    if (el) {
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        return { kind: 'button', x: r.x + r.width / 2, y: r.y + r.height / 2, text: el.innerText.trim(), tag: el.tagName };
    }
    return { kind: 'none', iframes: [...document.querySelectorAll('iframe')].map((f) => f.src.slice(0, 80)) };
})()`);
console.log("按钮:", JSON.stringify(target));
const before = await ev("location.href");
if (target?.kind !== "none") {
    for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
        await send("Input.dispatchMouseEvent", { type, x: target.x, y: target.y, button: "left", clickCount: 1 });
    }
    await sleep(8000);
    const after = await ev("location.href").catch(() => "(主页面已跳转)");
    const list = await pages();
    console.log("点击前主页面:", before.slice(0, 90));
    console.log("点击后主页面:", String(after).slice(0, 90));
    console.log("页面列表:", list.map((p) => p.url.slice(0, 90)));
    const popup = list.find((p) => p.id !== page.id && /accounts\.google\.com/.test(p.url));
    console.log(popup ? "结论：弹窗模式" : /accounts\.google\.com/.test(String(after)) ? "结论：整页跳转" : "结论：未判定");
}
ws.close();
