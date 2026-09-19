#!/usr/bin/env bash
# B07：视频窗口结束之后跑一个真实标签推理任务，确认模型切换与显存复用正常。
# 不写用户数据：标签包不做数据库访问，只读源图、写 TASK_RESULT_FILE。
set -u
ROOT=/opt/yuyutian/mytools/releases/image-extension-20260914-v3/task-packages/media_generate_tags/1.4.0
PY=/opt/yuyutian/mytools/releases/current/venv/bin/python3
WORK=/tmp/p0-b07
rm -rf "$WORK"; mkdir -p "$WORK"
cp /opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/input/p0-subject-a.png "$WORK/source.png"
SHA=$(sha256sum "$WORK/source.png" | cut -d' ' -f1)
cat > "$WORK/context.json" <<JSON
{"parameters": {"assetId": "00000000-0000-4000-8000-00000000b070",
                "contentSha256": "$SHA", "sourcePath": "$WORK/source.png",
                "filename": "source.png"}, "stepOutputs": {}}
JSON
echo "active package: $ROOT"
echo "lease hash: $(sha256sum "$ROOT/scripts/gpu_lease.py" | cut -c1-16)"
echo "vram before: $(nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader)"
START=$(date +%s)
env TASK_CONTEXT_FILE="$WORK/context.json" TASK_RESULT_FILE="$WORK/result.json" \
    TASK_ERROR_FILE="$WORK/error.json" TASK_WORK_DIR="$WORK/work" \
    DOWNLOAD_DESTINATION_ROOT="$WORK" \
    IMAGE_GPU_COORDINATION_VALIDATED=true \
    IMAGE_GPU_LOCK_FILE=/opt/yuyutian/mytools/runtime/image-generation/gpu.lock \
    IMAGE_COMFY_URL=http://127.0.0.1:8189 \
    TAGGING_SERVICE_URL=http://127.0.0.1:11434 \
    TAGGING_MODEL=huihui_ai/qwen3-vl-abliterated:8b \
    PYTHONPATH="/opt/yuyutian/mytools/releases/image-production-20260913-v5/task-executor-sdk" "$PY" "$ROOT/scripts/main.py"
STATUS=$?
END=$(date +%s)
echo "exit_status=$STATUS elapsed_s=$((END-START))"
echo "vram after: $(nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader)"
echo "ollama resident after: $(curl -s --max-time 5 http://127.0.0.1:11434/api/ps)"
echo "--- result ---"; head -c 700 "$WORK/result.json" 2>/dev/null || echo "(no result)"
echo; echo "--- error file ---"; head -c 300 "$WORK/error.json" 2>/dev/null || echo "(none)"
