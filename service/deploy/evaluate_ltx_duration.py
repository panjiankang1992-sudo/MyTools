#!/usr/bin/env python3
"""隔离比较较长时域条件，不更改已发布脚本包或生产模式开关。"""
import argparse
import fcntl
import json
from pathlib import Path
import shutil
import subprocess
import time
from PIL import Image, ImageOps
from ltx_candidate_graph import configure_graph
from wan22_candidate_graph import graph as wan22_graph
from video_candidate_cases import text_cases, text_dimensions
import deploy_video_generation as deploy
import evaluate_ltx_candidate as candidate
from video_acceptance_window import window, active_competitors, write_state


def main():
    """沿用正式独立素材，记录所有帧，异常时恢复共享 GPU 容量。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--variant', choices=['baseline', 'cfg2', 'end-guide', 'guided-cfg2', 'two-stage', 'two-stage-complete'], default='baseline')
    parser.add_argument('--prompt-style', choices=['original', 'action-first'], default='original')
    parser.add_argument('--model', choices=['ltx', 'wan22'], default='ltx')
    parser.add_argument('--input-mode', choices=['first-frame', 'text'], default='first-frame')
    parser.add_argument('--case', choices=['all', 'product', 'portrait', 'landscape'], default='all')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--seed', type=int, default=42)
    args = parser.parse_args()
    if not 60 <= args.timeout <= 1800:
        parser.error('timeout_out_of_range')
    if args.model == 'wan22' and args.variant != 'baseline':
        parser.error('wan22_requires_baseline_variant')
    if args.input_mode == 'text' and args.model != 'wan22':
        parser.error('text_mode_requires_wan22')
    if args.input_mode == 'text' and args.prompt_style != 'original':
        parser.error('text_mode_has_dedicated_prompts')
    if not 0 <= args.seed <= 2147483647:
        parser.error('invalid_seed')
    if args.case == 'product' and args.variant in ('end-guide', 'guided-cfg2'):
        parser.error('product_does_not_use_end_guide')
    run = args.output
    run.mkdir(parents=True, exist_ok=False)
    cases = [
        ('product', 'A red ceramic teapot, strawberries and a white plate sit on a wooden table in soft window light. The camera slowly pushes forward in a continuous shot. The teapot, strawberries and plate remain still and retain their colors and shapes.'),
        ('portrait', 'A woman looks directly at the camera. She blinks once, briefly closing both eyes and immediately opening them again, then smiles gently. Her head remains still. The camera is locked off. Preserve her face, hair, clothes and background.'),
        ('landscape', 'Snowy mountains and trees reflect in a lake beneath a colorful sunset. Small ripples continuously travel across the surface of the water. The camera stays completely still. The mountains, trees and sky remain unchanged.')]
    if args.input_mode == 'text':
        cases = text_cases()
    if args.prompt_style == 'action-first' and args.input_mode == 'first-frame':
        # 只改写表达顺序，保留原始动作和固定镜头要求用于同变量对照。
        cases = [
            ('product', 'The camera slowly dollies straight forward toward a red ceramic teapot. The teapot steadily grows larger at the center of the frame as the camera approaches it. Strawberries on a white plate stay motionless on the wooden table beside it. The red ceramic glaze, round handle and spout keep their shapes. Soft daylight from the window lights the still life. A single smooth continuous forward camera movement.'),
            ('portrait', 'The woman gives one quick natural blink and immediately opens her eyes. She then makes a gentle smile, looking at the camera with her eyes open for the rest of the shot. Her head stays upright and still throughout. She has short black hair and wears a beige coat. The street and buildings behind her remain in the same position. A tripod-mounted camera holds the same medium portrait framing for the entire shot in soft natural daylight.'),
            ('landscape', 'Small gentle ripples spread continuously across the lake, softly breaking the reflections of the snowy mountains. The water visibly shimmers with repeated ripples throughout the shot. The snowy mountain peaks, dark trees and colorful sunset sky stay in exactly the same place in the frame. A tripod-mounted camera holds a completely stationary wide view. The lake remains visible in the lower half of the frame throughout.')]
    # 尾帧锚点仅用于用户要求固定镜头的场景，不强行约束推进镜头。
    if args.variant in ('end-guide', 'guided-cfg2'):
        cases = [case for case in cases if case[0] != 'product']
    if args.case != 'all':
        cases = [case for case in cases if case[0] == args.case]
    results = []
    with window(deploy, run / 'window.json') as state:
        with (candidate.ROOT.parent / 'image-generation/gpu.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                queue = candidate.api('/queue')
                assert not queue['queue_running'] and not queue['queue_pending'], 'comfy_busy'
                candidate.api('/free', {'unload_models': True, 'free_memory': True})
                for name, prompt in cases:
                    if active_competitors(deploy, state['definitions']):
                        raise RuntimeError('production_gpu_task_arrived')
                    relative = None
                    if args.input_mode == 'text':
                        width, height = text_dimensions(name)
                    else:
                        source = Path('/tmp/ltx-fixtures') / (name + '.png')
                        shutil.copyfile(source, run / (name + '-source.png'))
                        with Image.open(source) as raw:
                            im = ImageOps.exif_transpose(raw).convert('RGB')
                            scale = 640 / max(im.size)
                            width, height = [max(32, round(n * scale / 32) * 32) for n in im.size]
                            im = im.resize((width, height), Image.Resampling.LANCZOS)
                        relative = run.name + '/' + name + '.png'
                        target = candidate.COMFY / 'input' / relative
                        target.parent.mkdir(exist_ok=True)
                        im.save(target)
                    if args.model == 'wan22':
                        graph = wan22_graph(relative if args.input_mode == 'first-frame' else None, run.name + '/' + name, args.seed, width, height, prompt)
                    else:
                        graph = candidate.graph(relative, run.name + '/' + name, args.seed, width, height, prompt)
                        graph = configure_graph(graph, width, height, args.variant, args.seed)
                    (run / (name + '-workflow.json')).write_text(json.dumps(graph, indent=2))
                    start = time.monotonic()
                    uid = candidate.api('/prompt', {'prompt': graph, 'client_id': run.name})['prompt_id']
                    write_state(run / 'prompt.json', {'promptId': uid})
                    last_resource_sample = 0.0
                    while True:
                        if args.model == 'wan22' and time.monotonic() - last_resource_sample >= 10:
                            # 低内存实验记录现场资源，并在系统余量过低时退出恢复窗口。
                            memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
                            available = int(memory['MemAvailable'].split()[0]) // 1024
                            with (run / 'memory-guard.jsonl').open('a') as resources:
                                resources.write(json.dumps({'seconds': round(time.monotonic() - start, 1),
                                    'case': name, 'availableMemoryMiB': available}) + '\n')
                            last_resource_sample = time.monotonic()
                            if available < 2048:
                                raise RuntimeError('candidate_memory_headroom_low')
                        if active_competitors(deploy, state['definitions']):
                            raise RuntimeError('production_gpu_task_arrived')
                        history = candidate.api('/history/' + uid).get(uid)
                        if history:
                            break
                        if time.monotonic() - start > args.timeout:
                            raise TimeoutError('candidate_timeout')
                        time.sleep(2)
                    (run / (name + '-history.json')).write_text(json.dumps(history, indent=2))
                    assert history['status']['status_str'] == 'success', 'comfy_failed'
                    images = history['outputs']['12']['images']
                    assert len(images) == 97
                    frames = run / (name + '-frames')
                    frames.mkdir()
                    for index, entry in enumerate(images):
                        path = (candidate.COMFY / 'output' / entry['subfolder'] / entry['filename']).resolve()
                        assert path.is_relative_to((candidate.COMFY / 'output').resolve())
                        shutil.copyfile(path, frames / f'{index:03d}.png')
                    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', '24', '-i', str(frames / '%03d.png'),
                                    '-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p', str(run / (name + '.mp4'))], check=True)
                    results.append({'name': name, 'frames': 97, 'fps': 24, 'seed': args.seed, 'model': args.model, 'inputMode': args.input_mode, 'variant': args.variant, 'promptStyle': args.prompt_style,
                                    'seconds': round(time.monotonic()-start, 2), 'status': 'SUCCEEDED'})
                    (run / 'summary.json').write_text(json.dumps({'samples': results, 'quality': 'pending', 'productionEnabled': False}, indent=2))
                    print(json.dumps(results[-1]), flush=True)
                    (run / 'prompt.json').unlink()
            finally:
                candidate.stop_owned(run)
                candidate.api('/free', {'unload_models': True, 'free_memory': True})


if __name__ == '__main__':
    main()
