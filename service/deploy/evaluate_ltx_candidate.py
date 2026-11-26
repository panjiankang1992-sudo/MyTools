#!/usr/bin/env python3
"""在可恢复窗口内验证 LTX 候选；不开放产品模式，不改写既有模型或输出。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

from video_acceptance_window import active_competitors, recover, window, write_state

ROOT = Path('/opt/yuyutian/mytools/runtime/video-generation')
COMFY = ROOT / 'runtime-v1/ComfyUI'
MODEL = 'ltxv-2b-0.9.8-distilled.safetensors'
TEXT = 't5xxl_fp8_e4m3fn_scaled.safetensors'
PROMPT = ('The subject in the frame begins to move gently. The camera slowly pushes in. '
          'The opening composition and colours stay unchanged.')
SOURCES = [('A-cold-white', '44fe02fb-a9cb-4106-889a-b9659113411f'),
           ('B-warm-generated', 'a97a45c2-08c5-4094-b6af-8f83c547c3a4'),
           ('C-landscape', '20323017-04e4-4623-a469-bbe622637609')]


def api(path, body=None):
    """仅访问本机 ComfyUI，不向外部发送素材。"""
    request = urllib.request.Request('http://127.0.0.1:8190' + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw.strip() else {}


def graph(image, prefix, seed, width=832, height=480, prompt=PROMPT):
    """单阶段八步候选；固定条件、采样器和时间步，不做事后颜色校正。"""
    def node(kind, **inputs):
        return {'class_type': kind, 'inputs': inputs}
    return {
        '1': node('CheckpointLoaderSimple', ckpt_name=MODEL),
        '2': node('CLIPLoader', clip_name=TEXT, type='ltxv', device='default'),
        '3': node('CLIPTextEncode', clip=['2', 0], text=prompt),
        '4': node('CLIPTextEncode', clip=['2', 0], text=''),
        '5': node('LoadImage', image=image),
        '6': node('LTXVImgToVideo', positive=['3', 0], negative=['4', 0], vae=['1', 2],
                  image=['5', 0], width=width, height=height, length=49, batch_size=1, strength=1.0),
        '7': node('LTXVConditioning', positive=['6', 0], negative=['6', 1], frame_rate=16.0),
        '8': node('KSamplerSelect', sampler_name='euler'),
        '9': node('ManualSigmas', sigmas='1, 0.9937, 0.9875, 0.9812, 0.975, 0.9094, 0.725, 0.4219, 0'),
        '10': node('SamplerCustom', model=['1', 0], add_noise=True, noise_seed=seed, cfg=1.0,
                   positive=['7', 0], negative=['7', 1], sampler=['8', 0], sigmas=['9', 0], latent_image=['6', 2]),
        '11': node('VAEDecode', samples=['10', 0], vae=['1', 2]),
        '12': node('SaveImage', images=['11', 0], filename_prefix=prefix),
    }


def stop_owned(run):
    """仅中断本次窗口的提示任务，确认结束后才允许恢复生产容量。"""
    path = run / 'prompt.json'
    if not path.exists():
        return
    prompt_id = json.loads(path.read_text())['promptId']
    queue = api('/queue')
    if any(item[1] == prompt_id for item in queue['queue_pending']):
        api('/queue', {'delete': [prompt_id]})
    if any(item[1] == prompt_id for item in queue['queue_running']):
        api('/interrupt', {})
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        queue = api('/queue')
        if not any(item[1] == prompt_id for key in ('queue_running', 'queue_pending') for item in queue[key]):
            path.unlink()
            return
        time.sleep(2)
    raise RuntimeError('candidate_prompt_still_running')


def prepare_inputs(deploy, run, native_aspect=False):
    """只读历史验收作业所引用的原图，并记录内容摘要。"""
    from PIL import Image, ImageOps
    import pymysql
    env = deploy.environment()
    db = pymysql.connect(host=env.get('VIDEO_GENERATION_DB_HOST', '127.0.0.1'),
        port=int(env.get('VIDEO_GENERATION_DB_PORT', '3306')),
        user=env.get('VIDEO_GENERATION_DB_USER', 'mytools_video_generation'),
        password=env['VIDEO_GENERATION_DB_PASSWORD'], database=env.get('VIDEO_GENERATION_DB_NAME', 'mytools_video_generation'))
    manifest = []
    with db, db.cursor() as cursor:
        for name, job in SOURCES:
            cursor.execute('SELECT upload_id,content_sha256 FROM video_input WHERE job_id=%s ORDER BY ordinal', (job,))
            upload, expected = cursor.fetchone()
            raw = (ROOT / 'uploads' / upload).read_bytes()
            assert hashlib.sha256(raw).hexdigest() == expected
            source = run / (name + '-source.png')
            shutil.copyfile(ROOT / 'uploads' / upload, source)
            image = ImageOps.exif_transpose(Image.open(source)).convert('RGB')
            width, height = (832, 480)
            if native_aspect:
                scale = min(640 / image.width, 640 / image.height)
                width = max(64, round(image.width * scale / 32) * 32)
                height = max(64, round(image.height * scale / 32) * 32)
            image = ImageOps.contain(image, (width, height), Image.Resampling.LANCZOS)
            canvas = Image.new('RGB', (width, height), (128, 128, 128))
            canvas.paste(image, ((width-image.width)//2, (height-image.height)//2))
            relative = run.name + '/' + name + '.png'
            target = COMFY / 'input' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.parent.chmod(0o755)
            canvas.save(target); target.chmod(0o644)
            manifest.append({'name': name, 'sourceJob': job, 'sourceSha256': expected, 'image': relative, 'width': width, 'height': height})
    (run / 'inputs.json').write_text(json.dumps(manifest, indent=2)+'\n')
    return manifest


def main():
    """验证固定样例并保存逐帧原图、视频和工作流；错误时恢复窗口。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--count', type=int, default=6, choices=range(1, 7))
    parser.add_argument('--recover', action='store_true')
    parser.add_argument('--native-aspect', action='store_true')
    parser.add_argument('--descriptive-prompts', action='store_true')
    args = parser.parse_args()
    import deploy_video_generation as deploy
    run = args.output
    if args.recover:
        stop_owned(run)
        api('/free', {'unload_models': True, 'free_memory': True})
        recover(deploy, run/'window.json')
        return
    run.mkdir(parents=True, exist_ok=True)
    assert (ROOT/'ltx-candidate-20260921/ready.json').exists(), 'models_not_ready'
    inputs = prepare_inputs(deploy, run, args.native_aspect)
    samples = []
    with window(deploy, run/'window.json') as state:
        with (ROOT.parent/'image-generation/gpu.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                queue = api('/queue')
                assert not queue['queue_running'] and not queue['queue_pending'], 'comfy_busy'
                api('/free', {'unload_models': True, 'free_memory': True})
                for index in range(args.count):
                    if active_competitors(deploy, state['definitions']):
                        raise RuntimeError('production_gpu_task_arrived')
                    item = inputs[index//2]; seed = (42, 932)[index % 2]
                    name = item['name'] + '-seed' + str(seed)
                    prompt = PROMPT
                    if args.descriptive_prompts:
                        descriptions = {
                            'A-cold-white': 'A celadon ceramic cup rests on its matching saucer on a pale tabletop. Warm natural light illuminates the ceramic glaze and the soft shadow. The cup and saucer remain stationary.',
                            'B-warm-generated': 'A green ceramic teapot rests on a pale stone table against a warm ochre wall. Natural sunlight casts a soft shadow. The teapot remains stationary and keeps its round body, spout, handle and lid.',
                            'C-landscape': 'Snow-covered mountains and dark evergreen forests surround a quiet lake beneath a pink sunset sky. The mountains, trees and pink sky reflect in the lake. Very gentle ripples move across the water.',
                        }
                        prompt = descriptions[item['name']] + ' The camera makes a slow, smooth forward push. Continuous single shot. Preserve the original colors, lighting and scene. No people, hands, new objects, cuts or scene changes.'
                    workflow = graph(item['image'], run.name+'/'+name, seed, item['width'], item['height'], prompt)
                    (run/(name+'-workflow.json')).write_text(json.dumps(workflow, indent=2)+'\n')
                    start = time.monotonic()
                    submitted = api('/prompt', {'prompt': workflow, 'client_id': run.name})
                    prompt_id = submitted['prompt_id']
                    write_state(run/'prompt.json', {'promptId': prompt_id})
                    deadline = start + 1200
                    while True:
                        if active_competitors(deploy, state['definitions']):
                            raise RuntimeError('production_gpu_task_arrived')
                        history = api('/history/'+prompt_id).get(prompt_id)
                        if history:
                            break
                        if time.monotonic() > deadline:
                            raise TimeoutError('candidate_inference_timeout')
                        time.sleep(3)
                    (run/(name+'-history.json')).write_text(json.dumps(history, indent=2)+'\n')
                    assert history['status']['status_str']=='success', 'candidate_comfy_failed'
                    images = history['outputs']['12']['images']
                    assert len(images) == 49, 'frame_count_mismatch'
                    frames = run/(name+'-frames'); frames.mkdir()
                    for frame, entry in enumerate(images):
                        source = (COMFY/'output'/entry['subfolder']/entry['filename']).resolve()
                        assert source.is_relative_to((COMFY/'output').resolve())
                        shutil.copyfile(source, frames/f'{frame:03d}.png')
                    subprocess.run(['ffmpeg','-v','error','-y','-framerate','16','-i',str(frames/'%03d.png'),
                        '-c:v','libx264','-crf','18','-pix_fmt','yuv420p',str(run/(name+'.mp4'))],check=True)
                    samples.append({'name': name, 'seed': seed, 'frames':49, 'elapsedSeconds':round(time.monotonic()-start,2),
                                    'status':'SUCCEEDED','promptId':prompt_id})
                    (run/'summary.json').write_text(json.dumps({'samples':samples,'qualityVerdict':'pending'},indent=2)+'\n')
                    print(json.dumps(samples[-1]),flush=True)
                    (run/'prompt.json').unlink()
            finally:
                stop_owned(run)
                api('/free', {'unload_models': True, 'free_memory': True})


if __name__ == '__main__':
    main()
