// 打开一个登录弹窗供截图检查界面（顶栏、关闭按钮），之后由 adb 截图 / 按返回键验证
const page = (await (await fetch("http://127.0.0.1:9333/json")).json()).find((p) => p.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((ok) => (ws.onopen = ok));
ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id === 1) { console.log("window.open:", m.result?.result?.value); ws.close(); } };
const url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&redirect_uri=https%3A%2F%2Fwww.physicsforums.com%2F&response_type=code&scope=email";
ws.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression: `String(!!window.open(${JSON.stringify(url)}, 'login'))`, returnByValue: true, userGesture: true } }));
