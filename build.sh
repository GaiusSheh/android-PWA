#!/usr/bin/env bash
# 一条命令构建 release APK，输出到 dist/<name>-<version>.apk
#
#   ./build.sh                  构建 apps.json 里的全部 App + 工厂 App
#   ./build.sh wikipedia        只构建指定 id（可以写多个：./build.sh wikipedia hackernews）
#   ./build.sh factory          只构建工厂 App（手机上输入网址生成 App 的那个；内含你的签名私钥，不要外传）
#   ./build.sh factory-public   构建可公开发布的工厂（不含私钥，生成的 App 用手机上自生成的密钥签名；需要正式 keystore）
#   ./build.sh --list           列出 apps.json 里的全部 id
#
# 签名：有 keystore.properties 或 WEBSHELL_KEYSTORE_* 环境变量时用正式密钥，否则退回 debug 签名（会有警告）。
# Windows 上请在 Git Bash 中运行。
set -euo pipefail

cd "$(dirname "$0")"

list_ids() {
    # 不依赖 jq：apps.json 里每个条目的 "id" 字段
    grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' apps.json | sed 's/.*"\([^"]*\)"$/\1/'
}

if [[ "${1:-}" == "--list" ]]; then
    list_ids
    exit 0
fi
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,11p' "$0"
    exit 0
fi

tasks=()
gradle_args=()
if [[ $# -eq 0 ]]; then
    tasks+=("distRelease" "distFactoryRelease")
else
    if [[ " $* " == *" factory "* && " $* " == *" factory-public "* ]]; then
        echo "错误：factory 和 factory-public 不能在同一次构建里，请分两次运行" >&2
        exit 1
    fi
    known_ids=" $(list_ids | tr '\n' ' ') "
    for id in "$@"; do
        if [[ "$id" == "factory" ]]; then
            tasks+=("distFactoryRelease")
            continue
        fi
        if [[ "$id" == "factory-public" ]]; then
            tasks+=("distFactoryRelease")
            gradle_args+=("-Pwebshell.publicFactory=true")
            continue
        fi
        if [[ "$known_ids" != *" $id "* ]]; then
            echo "错误：apps.json 中没有 id \"$id\"。可用的 id：$known_ids" >&2
            exit 1
        fi
        # 任务名 = dist + 首字母大写的 id + Release，例如 distWikipediaRelease
        first=$(printf '%s' "${id:0:1}" | tr '[:lower:]' '[:upper:]')
        tasks+=("dist${first}${id:1}Release")
    done
fi

./gradlew "${tasks[@]}" ${gradle_args[@]+"${gradle_args[@]}"} --console=plain

echo
echo "输出目录 dist/："
ls -1 dist/*.apk
