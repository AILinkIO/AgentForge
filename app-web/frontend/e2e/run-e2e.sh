#!/usr/bin/env bash
# 网页端端到端测试：真模型 + 真后端 + Playwright。
#
#   app-web/frontend/e2e/run-e2e.sh [playwright 参数…]
#
# 环境变量（都有缺省）：
#   E2E_ENV       要 source 的 key 文件（缺省 ~/env/minimax.env，里面 export MINIMAX_API_KEY）
#   E2E_PROVIDER  provider id（minimax）          E2E_API      线格式（anthropic）
#   E2E_BASE      provider 基地址（https://api.minimaxi.com/anthropic）
#   E2E_MODEL     模型 id（MiniMax-M3.1-Flash）   E2E_PORT     后端端口（18788）
# 配置目录与工作根都是临时目录（不碰 ~/.config/agentforge），跑完删掉；后端进程跑完杀掉。
# 后端起来时没有任何项目：测试自己在网页上把工作根加成项目（走文件夹选择器）。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FRONT="$(dirname "$HERE")"
REPO="$(cd "$FRONT/../.." && pwd)"
JAR="$REPO/app-web/build/libs/agentforge-web.jar"
[ -f "$JAR" ] || { echo "先构建：./gradlew :app-web:bootJar -Pbitcask.lib=…" >&2; exit 2; }

E2E_ENV="${E2E_ENV:-$HOME/env/minimax.env}"
E2E_PROVIDER="${E2E_PROVIDER:-minimax}"
E2E_API="${E2E_API:-anthropic}"
E2E_BASE="${E2E_BASE:-https://api.minimaxi.com/anthropic}"
E2E_MODEL="${E2E_MODEL:-MiniMax-M3.1-Flash}"
E2E_PORT="${E2E_PORT:-18788}"
[ -f "$E2E_ENV" ] || { echo "找不到 key 文件 $E2E_ENV" >&2; exit 2; }
set -a; . "$E2E_ENV"; set +a

WORK="$(mktemp -d /tmp/afwebe2e-XXXXXX)"
HOME_DIR="$WORK/home"; ROOT="$WORK/root"
mkdir -p "$HOME_DIR" "$ROOT/notes" "$ROOT/src"
SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

cat > "$HOME_DIR/providers.json" <<JSON
{"providers": {"$E2E_PROVIDER": {"api": "$E2E_API", "baseUrl": "$E2E_BASE", "label": "E2E",
   "models": {"$E2E_MODEL": {"context": 200000, "maxOutput": 16000}}}},
 "model": "$E2E_PROVIDER/$E2E_MODEL"}
JSON
# 工作根里放一个小项目
echo 'PINEAPPLE-42' > "$ROOT/notes/secret-word.txt"
printf '# demo\n\n一个给端到端测试用的小项目。\n' > "$ROOT/README.md"
printf 'def add(a, b):\n    return a + b\n\n\ndef mul(a, b):\n    return a * b\n' > "$ROOT/src/calc.py"

OPTS=(--enable-native-access=ALL-UNNAMED)
[ -f "$REPO/sandbox/build/native/libagentforge_sandbox_native.so" ] && OPTS+=("-Dagentforge.sandbox.native.dir=$REPO/sandbox/build/native")
AGENTFORGE_HOME="$HOME_DIR" java "${OPTS[@]}" -jar "$JAR" --server.port="$E2E_PORT" \
  --agentforge.web.approval=untrusted --agentforge.web.sandbox=workspace-write \
  --agentforge.web.mcp=false > "$WORK/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 60); do curl -sf "http://127.0.0.1:$E2E_PORT/api/info" >/dev/null && break; sleep 0.5; done
curl -sf "http://127.0.0.1:$E2E_PORT/api/info" >/dev/null || { echo "后端没起来：" >&2; tail -40 "$WORK/server.log" >&2; exit 1; }

cd "$FRONT"
set +e
E2E_BASE_URL="http://127.0.0.1:$E2E_PORT" E2E_ROOT="$ROOT" E2E_HOME="$HOME_DIR" E2E_MODEL_REF="$E2E_PROVIDER/$E2E_MODEL" \
  E2E_MODEL_ID="$E2E_MODEL" E2E_PROVIDER_BASE="$E2E_BASE" npx playwright test "$@"
code=$?
set -e
[ $code -ne 0 ] && { echo "── 后端日志（尾部）──" >&2; tail -60 "$WORK/server.log" >&2; }
exit $code
