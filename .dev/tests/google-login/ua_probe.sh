#!/usr/bin/env bash
# 探路：Google OAuth 授权页对不同 User-Agent / X-Requested-With 的反应（服务端层面，不涉及账号）
# 用法：./ua_probe.sh '<accounts.google.com/o/oauth2/auth?... 完整地址>'
# 输出：每种组合的 HTTP 状态码，以及页面里是否出现 disallowed_useragent
set -uo pipefail

url="$1"
out="$(dirname "$0")/output"
mkdir -p "$out"

CHROME_VER="141.0.0.0"
declare -A UAS=(
    [webview_default]="Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${CHROME_VER} Mobile Safari/537.36"
    [webview_no_wv]="Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${CHROME_VER} Mobile Safari/537.36"
    [chrome_mobile]="Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VER} Mobile Safari/537.36"
)

for name in webview_default webview_no_wv chrome_mobile; do
    for xrw in none pkg; do
        args=(-s -L -o "$out/$name-$xrw.html" -w "%{http_code} %{url_effective}" -A "${UAS[$name]}")
        [[ "$xrw" == pkg ]] && args+=(-H "X-Requested-With: com.yuanfeng.webshell.physicsforums")
        code=$(curl "${args[@]}" "$url")
        if grep -q "disallowed_useragent" "$out/$name-$xrw.html"; then verdict="BLOCKED(disallowed_useragent)"; else verdict="ok"; fi
        printf '%-16s xrw=%-4s http=%s %s\n' "$name" "$xrw" "$code" "$verdict"
    done
done
