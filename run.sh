#!/usr/bin/env bash
# 桌面交互入口：在电脑上测试规则 / 搜索 / 播放
set -e
cd "$(dirname "$0")"

if [ ! -d core/out ]; then
  echo "还没编译，先执行 ./build.sh"
  exit 1
fi

CONFIG="config/demo-safe.json"
if [ ! -f "$CONFIG" ]; then
  echo "没找到 $CONFIG，从内置示例复制一份 …"
  mkdir -p config
  cp app/assets/config/demo-safe.json "$CONFIG"
fi

echo "配置文件: $CONFIG"
echo "提示：如果你的源在内网/局域网，加 --allow-private"
echo
exec java -cp core/out com.tvbox.core.cli.Desktop -c "$CONFIG" "$@"
