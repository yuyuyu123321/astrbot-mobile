#!/system/bin/sh
# 生成 AstrBot Mobile 的内置 rootfs 归档（app/src/main/assets/rootfs/rootfs-template.bin）
#
# 在设备上执行（需要已有可用的 rootfs 实例，例如已通过旧版完成过部署）：
#   run-as com.astrbot.mobile sh build_rootfs_template.sh
#
# 产物：app/src/main/assets/rootfs/rootfs-template.bin
#
# 注意：
# - 归档会剥离所有用户私有数据（密码、数据库、插件、日志、缓存），
#   分发出去不会泄露任何账号信息。
# - 扩展名必须是 .bin。AAPT2 会把 .gz/.tar/.zip 当作归档自动解包并改名，
#   导致 assets 内文件名变化、体积翻倍且 noCompress 失效。
set -u

RFS="${1:-files/runtime/rootfs}"
OUT="${2:-app/src/main/assets/rootfs/rootfs-template.bin}"

[ -d "$RFS" ] || { echo "ERR: rootfs 不存在: $RFS"; exit 1; }
[ -f "$RFS/root/AstrBot/main.py" ] || { echo "ERR: 未找到 AstrBot 本体"; exit 1; }
[ -f "$RFS/usr/bin/python3" ] || { echo "ERR: 未找到 python3"; exit 1; }

echo "=== 清理私有数据与构建垃圾 ==="
cd "$RFS" || exit 1

purge_file() { [ -e "$1" ] && rm -f "$1"; }
purge_dir()  { [ -d "$1" ] && rm -r "$1"; }

# 凭据与业务数据
purge_file root/AstrBot/data/cmd_config.json
purge_file root/AstrBot/data/data_v4.db
purge_file root/AstrBot/data/data_v4.db-wal
purge_file root/AstrBot/data/data_v4.db-shm
purge_file root/AstrBot/data/mcp_server.json
purge_dir  root/AstrBot/data/knowledge_base
purge_dir  root/AstrBot/data/logs
purge_dir  root/AstrBot/data/attachments
purge_dir  root/AstrBot/data/temp
purge_dir  root/AstrBot/data/plugins
purge_dir  root/AstrBot/data/webchat
# 缓存与历史
purge_dir  root/.cache
purge_dir  root/.local
purge_dir  root/.config
purge_file root/.ash_history
purge_file root/.bash_history
purge_file root/.python_history
# 字节码与版本库
find . -name "__pycache__" -type d -prune -exec rm -r {} + 2>/dev/null
find . -name "*.pyc" -delete 2>/dev/null
purge_dir  root/AstrBot/.git
purge_dir  root/AstrBot/tests
purge_dir  root/AstrBot/docs
# proot 运行残留
rm -rf tmp/prooted-* 2>/dev/null

mkdir -p root/AstrBot/data/plugins root/AstrBot/data/attachments \
         root/AstrBot/data/temp root/AstrBot/data/logs \
         root/AstrBot/data/config root/AstrBot/data/knowledge_base \
         root/AstrBot/data/webchat
mkdir -p tmp var/tmp var/log dev proc sys

printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > etc/resolv.conf

echo "=== 打包 ==="
mkdir -p "$(dirname "$OUT")" 2>/dev/null
cd "$RFS" || exit 1
tar -czf "$OUT" \
  --exclude="*__pycache__*" \
  --exclude="*.pyc" \
  --exclude="tmp/*" \
  --exclude="var/log/*" \
  -C "$RFS" .

RC=$?
echo "TAR_RC=$RC"
[ $RC -eq 0 ] && echo "SIZE=$(stat -c %s "$OUT")" && echo "SHA256=$(sha256sum "$OUT" | cut -d' ' -f1)"
echo "=== 完成 ==="