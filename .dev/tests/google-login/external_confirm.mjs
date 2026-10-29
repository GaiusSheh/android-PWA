// 外部跳转确认框回归（logintest，allowedHosts 含 httpbin.org）
// A 非点击跳站外 → 确认框；B 点击跳站外 → 直接打开；C 点击 → 站内 302 → 站外 → 直接打开；D 非点击 → 站内 302 → 站外 → 确认框
// 「直接打开」看 logcat 的 ActivityTaskManager START ... VIEW；「确认框」= 没有 VIEW 且主页面不变，另存截图 output/confirm_<case>.png 人工核对
// （WSA 的 dumpsys window 拿不到焦点窗口，无法自动判断对话框）
// 用法：node external_confirm.mjs <adb 设备序列号> <包名>
import { execFileSync } from "node:child_process";
import path from "node:path";

const [SERIAL, PKG] = process.argv.slice(2);
if (!SERIAL || !PKG) throw new Error("用法：node external_confirm.mjs <adb 设备序列号> <包名>");
const OUT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, "$1")), "output");
const adb = (...args) => execFileSync("adb", ["-s", SERIAL, ...args], { encoding: "utf8", maxBuffer: 64 << 20, env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const HOME = "https://httpbin.org/";

async function attach() {
    const page = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((p) => p.type === "page");
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((ok) => (ws.onopen = ok));
    let seq = 0;
    const pending = new Map();
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); } };
    const send = (method, params = {}) => new Promise((ok) => { const id = ++seq; pending.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });
    const ev = async (expression, userGesture = false) => (await send("Runtime.evaluate", { expression, returnByValue: true, userGesture }))?.result?.value;
    return { ws, send, ev };
}

const viewTo = (needle) => adb("logcat", "-d", "-s", "ActivityTaskManager:I").split("\n").some((l) => /START .*VIEW/.test(l) && l.includes(needle));
const shot = (name) => { adb("shell", "screencap", "-p", "/sdcard/s.png"); adb("pull", "/sdcard/s.png", path.join(OUT, `confirm_${name}.png`)); };
const results = [];
const check = (name, ok, detail = "") => { results.push(ok); console.log(`${ok ? "✓" : "✗"} ${name}${detail ? `  (${detail})` : ""}`); };

async function runCase(name, target, gesture, expectDirect) {
    adb("shell", "am", "start", "-n", `${PKG}/com.yuanfeng.webshell.MainActivity`); // 外部打开后切回 App
    await sleep(2000);
    const c = await attach();
    await c.send("Page.navigate", { url: HOME });
    await sleep(5000);
    adb("logcat", "-c");
    const js = gesture ? `location.href = ${JSON.stringify(target)}; true` : `setTimeout(() => { location.href = ${JSON.stringify(target)}; }, 0); true`;
    await c.ev(js, gesture);
    await sleep(5000);
    const host = new URL(target.includes("redirect-to") ? decodeURIComponent(target.split("url=")[1]) : target).host;
    const direct = viewTo(host);
    const href = await c.ev("location.href");
    if (expectDirect) {
        check(`${name} 直接交给外部`, direct);
    } else {
        check(`${name} 没有直接交给外部`, !direct);
        check(`${name} 主页面不变（等待确认）`, href === HOME, href.slice(0, 60));
        shot(name);
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); // 关掉确认框
        await sleep(1500);
    }
    c.ws.close();
}

await runCase("A 非点击跳站外", "https://example.com/?case=A", false, false);
await runCase("B 点击跳站外", "https://example.com/?case=B", true, true);
await runCase("C 点击→站内302→站外", "https://httpbin.org/redirect-to?url=https%3A%2F%2Fexample.net%2F%3Fcase%3DC", true, true);
await runCase("D 非点击→站内302→站外", "https://httpbin.org/redirect-to?url=https%3A%2F%2Fexample.org%2F%3Fcase%3DD", false, false);

console.log(`\n${results.filter(Boolean).length}/${results.length} 通过（A、D 的确认框见 output/confirm_*.png）`);
process.exit(results.every(Boolean) ? 0 : 1);
