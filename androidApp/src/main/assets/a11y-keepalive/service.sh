#!/system/bin/sh
# 流光无障碍保活：Root 模块后端。
# 由 KernelSU / Magisk 在开机 late_start 阶段以 root 执行；只处理配置里列出的组件。
MODDIR=${0%/*}
STATE=/data/adb/flowglass
CONF=$STATE/a11y_keepalive.conf
LOG=$STATE/a11y_keepalive.log
PIDFILE=$STATE/a11y_keepalive.pid
INTERVAL=30

mkdir -p "$STATE"

# 单实例：开机脚本与「立即启动」可能同时拉起。
if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then exit 0; fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"; exit 0' TERM INT

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

trim_log() {
  [ -f "$LOG" ] || return 0
  lines=$(wc -l < "$LOG" 2>/dev/null)
  case "$lines" in ''|*[!0-9]*) return 0;; esac
  [ "$lines" -gt 200 ] || return 0
  tail -n 100 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}

log "started pid=$$"
while true; do
  sleep "$INTERVAL"
  [ -f "$CONF" ] || continue
  [ "$(settings get secure accessibility_enabled)" = "1" ] || continue
  cur=$(settings get secure enabled_accessibility_services)
  case "$cur" in ''|null) continue;; esac
  changed=0
  while IFS= read -r comp; do
    [ -n "$comp" ] || continue
    # 只处理仍然在启用列表里的组件：用户移除后绝不加回。
    case ":$cur:" in *":$comp:"*) ;; *) continue;; esac
    pkg=${comp%%/*}
    # 进程还在就认为绑定有效；进程消失才是本兜底要处理的场景。
    pidof "$pkg" >/dev/null 2>&1 && continue
    # 尊重 force-stop。
    stopped=$(dumpsys package "$pkg" 2>/dev/null | grep -m1 'stopped=')
    case "$stopped" in *stopped=true*) continue;; esac
    stamp="$STATE/last_$(echo "$comp" | tr '/.' '__')"
    now=$(date +%s)
    last=$(cat "$stamp" 2>/dev/null || echo 0)
    case "$last" in ''|*[!0-9]*) last=0;; esac
    [ $((now - last)) -lt 60 ] && continue
    echo "$now" > "$stamp"
    log "rebind $comp"
    without=""
    for c in $(echo "$cur" | tr ':' ' '); do
      [ "$c" = "$comp" ] && continue
      without="${without:+$without:}$c"
    done
    settings put secure enabled_accessibility_services "$without"
    sleep 2
    settings put secure enabled_accessibility_services "$cur"
    sleep 2
    after=$(settings get secure enabled_accessibility_services)
    case ":$after:" in
      *":$comp:"*) ;;
      *) settings put secure enabled_accessibility_services "$cur"; log "restore failed $comp";;
    esac
    cur=$(settings get secure enabled_accessibility_services)
    changed=1
  done < "$CONF"
  [ "$changed" = 1 ] && trim_log
done
