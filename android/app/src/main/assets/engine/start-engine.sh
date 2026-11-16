#!/system/bin/sh
# 引擎启动脚本 —— 完全照 dsh-go 实测的 argv 写（见 _ref 里的 /proc 抓包）。
# 作用：用 proot 把 rootfs 跑起来，在里面启动 `dsh web`，监听 127.0.0.1:3080。
#
# 参数（由 Kotlin 侧传进来）：
#   $1 = APP_FILES   应用私有目录，如 /data/user/0/com.you.app/files
#   $2 = NATIVE_LIB  nativeLibraryDir，proot 的 so 在这里
#   $3 = PORT        引擎端口（默认 3080）
#
# 注意：本脚本由 App 以 rootfs 之外的身份运行（Android shell），不要在里面依赖宿主 bash 特性。

set -e
APP_FILES="${1:?missing APP_FILES}"
NATIVE_LIB="${2:?missing NATIVE_LIB}"
PORT="${3:-3080}"

ROOTFS="$APP_FILES/runtime/rootfs"
PROOT="$NATIVE_LIB/libproot.so"
[ -d "$ROOTFS" ] || { echo "rootfs missing: $ROOTFS" >&2; exit 1; }
[ -f "$PROOT" ]  || { echo "proot missing: $PROOT" >&2; exit 1; }

# proot 需要的两个挂载点（dsh-go 也是这么造的）
mkdir -p "$ROOTFS/var/lib/dsh-app/empty" "$ROOTFS/var/lib/dsh-app/shm"
mkdir -p "$APP_FILES/runtime/dev-view"
for d in null zero full urandom tty ptmx; do
  [ -e "/dev/$d" ] && ln -sf "/dev/$d" "$APP_FILES/runtime/dev-view/$d" 2>/dev/null || true
done
ln -sf /dev/urandom "$APP_FILES/runtime/dev-view/random" 2>/dev/null || true
ln -sf /proc/self/fd "$APP_FILES/runtime/dev-view/fd" 2>/dev/null || true

# patch 链：你自己加的补丁放这里（base 必须有）
PATCHES="--patch /etc/app/base.patch.yml"
[ -f /etc/app/mytools.patch.yml ] && PATCHES="$PATCHES --patch /etc/app/mytools.patch.yml"
[ -f "$ROOTFS/etc/app/mytools.patch.yml" ] && PATCHES="$PATCHES --patch /etc/app/mytools.patch.yml"

exec "$PROOT" \
  --kill-on-exit --link2symlink --sysvipc -L -0 \
  --rootfs="$ROOTFS" --cwd=/root \
  -b "$APP_FILES/runtime/dev-view:/dev" \
  -b /dev/urandom:/dev/random \
  -b /proc:/proc \
  -b /proc/self/fd:/dev/fd \
  -b /proc/self/fd/0:/dev/stdin \
  -b /proc/self/fd/1:/dev/stdout \
  -b /proc/self/fd/2:/dev/stderr \
  -b /sys:/sys \
  -b "$ROOTFS/var/lib/dsh-app/empty:/sys/fs/selinux" \
  -b "$ROOTFS/var/lib/dsh-app/shm:/dev/shm" \
  -b /system:/system \
  -b /apex:/apex \
  -b /storage/emulated/0:/sdcard \
  -b /storage:/storage \
  -- /usr/bin/env -i \
    HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp \
    DSH_HOME=/root/.dsh \
    XDG_DATA_DIRS=/usr/local/share:/usr/share:/system \
    pnpm_config_registry=https://registry.npmmirror.com/ \
    UV_USE_IO_URING=0 UV_THREADPOOL_SIZE=2 \
    NARB_DISABLE_NATIVE_CACHE=1 \
    NODE_OPTIONS="--dns-result-order=ipv4first --max-old-space-size=1536 --import=/opt/dsh-go/direct/proot-dns.mjs" \
    DSH_GO_RUNTIME_ROOT="$ROOTFS" \
    /bin/sh -c "exec /usr/local/bin/dsh web $PATCHES --host 127.0.0.1 --port $PORT --no-open"
