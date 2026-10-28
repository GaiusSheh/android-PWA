"""通过 adb 驱动 WSA / 真机上的界面，用于端到端自测（探索性工具，放在 .dev/tests/）。

用法（在工程根目录）：
  python .dev/tests/wsa/ui.py shot <名字>            截图到 .dev/tests/wsa/output/<名字>.png
  python .dev/tests/wsa/ui.py nodes                  列出界面上有文字 / id 的节点
  python .dev/tests/wsa/ui.py tap-id <resource-id>   点击 id 匹配的节点（可只写 id 末尾，如 fetch）
  python .dev/tests/wsa/ui.py tap-text <文字>         点击文字包含该内容的节点
  python .dev/tests/wsa/ui.py type <文本>             向当前焦点输入框输入 ASCII 文本
  python .dev/tests/wsa/ui.py key <keycode>          发送按键，如 BACK、ENTER
环境变量 ADB_SERIAL 指定设备，默认 127.0.0.1:58526（WSA）。
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get("ADB", r"C:\Android\sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "127.0.0.1:58526")
OUT = Path(__file__).resolve().parent / "output"


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} 失败：{r.stderr or r.stdout}")
    return r.stdout


def shot(name: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "screencap", "-p", "/sdcard/webshell_shot.png")
    adb("pull", "/sdcard/webshell_shot.png", str(OUT / f"{name}.png"))
    print(OUT / f"{name}.png")


def dump() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/sdcard/webshell_ui.xml")
    OUT.mkdir(parents=True, exist_ok=True)
    local = OUT / "ui.xml"
    adb("pull", "/sdcard/webshell_ui.xml", str(local))
    return ET.parse(local).getroot()


def center(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(pred) -> ET.Element:
    for n in dump().iter("node"):
        if pred(n):
            return n
    raise SystemExit("找不到匹配的节点")


def main() -> None:
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "shot":
        shot(args[0])
    elif cmd == "nodes":
        for n in dump().iter("node"):
            text, rid = n.get("text", ""), n.get("resource-id", "")
            if text or rid:
                print(f"{rid.split('/')[-1]:<22} {n.get('class','').split('.')[-1]:<20} {text[:50]!r} {n.get('bounds')}")
    elif cmd == "tap-id":
        n = find(lambda n: n.get("resource-id", "").endswith("/" + args[0]) or n.get("resource-id") == args[0])
        adb("shell", "input", "tap", *map(str, center(n)))
    elif cmd == "tap-text":
        n = find(lambda n: args[0] in n.get("text", "") or args[0] in n.get("content-desc", ""))
        adb("shell", "input", "tap", *map(str, center(n)))
    elif cmd == "type":
        adb("shell", "input", "text", args[0].replace(" ", "%s"))
    elif cmd == "key":
        adb("shell", "input", "keyevent", "KEYCODE_" + args[0].upper())
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
