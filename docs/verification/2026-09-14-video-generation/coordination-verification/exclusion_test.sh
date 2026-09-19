#!/usr/bin/env bash
# B06：确认租约真的互斥——锁被别人持有时标签推理不会同时占用 GPU。
# 只持锁 10 秒（不等到 120 秒超时），并把显存变化作为"是否真的开始推理"的证据。
set -u
ROOT=/opt/yuyutian/mytools/releases/image-extension-20260914-v3/task-packages/media_generate_tags/1.4.0
SDK=/opt/yuyutian/mytools/releases/image-production-20260913-v5/task-executor-sdk
PY=/opt/yuyutian/mytools/releases/current/venv/bin/python3
LOCK=/opt/yuyutian/mytools/runtime/image-generation/gpu.lock
WORK=/tmp/p0-b06
rm -rf "$WORK"; mkdir -p "$WORK"
cp /opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/input/p0-subject-a.png "$WORK/source.png"
SHA=$(sha256sum "$WORK/source.png" | cut -d' ' -f1)
echo "{\"parameters\": {\"assetId\": \"00000000-0000-4000-8000-00000000b060\", \"contentSha256\": \"$SHA\", \"sourcePath\": \"$WORK/source.png\", \"filename\": \"source.png\"}, \"stepOutputs\": {}}" > "$WORK/context.json"

echo "1) 先卸载标签模型，回到基线"
curl -s --max-time 20 http://127.0.0.1:11434/api/generate -d '{"model":"huihui_ai/qwen3-vl-abliterated:8b","keep_alive":0}' > /dev/null
sleep 3
echo "   vram: $(nvidia-smi --query-gpu=memory.used --format=csv,noheader)"

echo "2) 另起进程持有共享锁 10 秒（扮演视频运行时）"
python3 -c "
import fcntl, time
h = open('$LOCK', 'a')
fcntl.flock(h, fcntl.LOCK_EX)
print('   lock held'); time.sleep(10); h.close(); print('   lock released')
" &
HOLDER=$!
sleep 2

echo "3) 在锁被持有期间启动真实标签推理"
env TASK_CONTEXT_FILE="$WORK/context.json" TASK_RESULT_FILE="$WORK/result.json" \
    TASK_ERROR_FILE="$WORK/error.json" TASK_WORK_DIR="$WORK/work" DOWNLOAD_DESTINATION_ROOT="$WORK" \
    PYTHONPATH="$SDK" IMAGE_GPU_COORDINATION_VALIDATED=true IMAGE_GPU_LOCK_FILE="$LOCK" \
    IMAGE_COMFY_URL=http://127.0.0.1:8189 TAGGING_SERVICE_URL=http://127.0.0.1:11434 \
    TAGGING_MODEL=huihui_ai/qwen3-vl-abliterated:8b \
    "$PY" "$ROOT/scripts/main.py" > "$WORK/stdout.log" 2>&1 &
WORKER=$!
sleep 6
echo "   推理进行中(vram): $(nvidia-smi --query-gpu=memory.used --format=csv,noheader)  <- 仍应接近基线"
echo "   result 已生成? $([ -f "$WORK/result.json" ] && echo yes || echo no)"
wait $HOLDER
echo "4) 锁释放后等待推理完成"
wait $WORKER
echo "   exit_status=$?"
sleep 2
echo "   vram: $(nvidia-smi --query-gpu=memory.used --format=csv,noheader)"
echo "   result: $(head -c 220 "$WORK/result.json" 2>/dev/null || echo '(none)')"
