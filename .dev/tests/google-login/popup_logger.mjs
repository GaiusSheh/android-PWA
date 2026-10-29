// 记录弹窗式登录：每秒列一次 WebView 页面，记下弹窗的出现 / 关闭和主页面地址变化
// 弹窗出现过并关闭后再观察 20 秒结束，或超时。用法：node popup_logger.mjs <主页面地址前缀> [超时秒数，默认 600]
// 输出同时写入 output/popup_log.txt
import fs from "node:fs";
import path from "node:path";

const [mainPrefix, timeoutArg] = process.argv.slice(2);
if (!mainPrefix) throw new Error("用法：node popup_logger.mjs <主页面地址前缀> [超时秒数]");
const OUT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, "$1")), "output", "popup_log.txt");
const lines = [];
const log = (s) => { const line = `${new Date().toISOString().slice(11, 19)} ${s}`; console.log(line); lines.push(line); fs.writeFileSync(OUT, lines.join("\n") + "\n"); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const end = Date.now() + Number(timeoutArg ?? 600) * 1000;
let last = "";
let popupSeen = false;
let closedAt = 0;
log(`开始记录，主页面前缀 ${mainPrefix}`);
while (Date.now() < end) {
    let pages;
    try {
        pages = (await (await fetch("http://127.0.0.1:9333/json")).json()).filter((p) => p.type === "page");
    } catch (e) {
        log(`无法读取页面列表：${e.message}`);
        await sleep(2000);
        continue;
    }
    const snapshot = pages.map((p) => p.url.slice(0, 110)).join("  |  ");
    if (snapshot !== last) {
        log(`${pages.length} 个页面：${snapshot}`);
        last = snapshot;
    }
    const hasPopup = pages.some((p) => !p.url.startsWith(mainPrefix) && /accounts\.google\.com/.test(p.url));
    if (hasPopup) popupSeen = true;
    if (popupSeen && !hasPopup && !closedAt) {
        closedAt = Date.now();
        log("弹窗已关闭，再观察 20 秒");
    }
    if (closedAt && Date.now() - closedAt > 20000) break;
    await sleep(1000);
}
log("结束");
