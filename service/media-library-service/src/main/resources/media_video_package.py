"""为本机已授权的普通视频生成原子发布的手机播放副本。"""
import json
import struct
from pathlib import Path
import subprocess
import sys

source, output = map(Path, sys.argv[1:3])
# 进程工作目录是私有暂存目录，只读取已物化的单个媒体文件。
input_flags = ['-protocol_whitelist', 'file', '-format_whitelist', 'mov,matroska,webm,avi']
probe = subprocess.run(['ffprobe', '-v', 'error', *input_flags, '-show_entries',
                        'stream=codec_type,codec_name,width,height:format=duration,format_name', '-of', 'json', str(source)],
                       capture_output=True, check=True, timeout=30)
info = json.loads(probe.stdout)
video = next(s for s in info['streams'] if s.get('codec_type') == 'video')
duration = float(info['format']['duration'])
if not 0 < duration <= 4 * 3600 or video['width'] * video['height'] > 3840 * 2160:
    raise ValueError('video_package_limits')
output.mkdir(mode=0o700, exist_ok=True)
def tail_index(path):
    """只检查顶层容器头，跳过大块媒体数据。"""
    seen_data = False
    length = path.stat().st_size
    with path.open('rb') as stream:
        for _ in range(4096):
            pos = stream.tell()
            header = stream.read(8)
            if len(header) != 8:
                return False
            size, kind = struct.unpack('>I4s', header)
            if size == 1:
                size = struct.unpack('>Q', stream.read(8))[0]
            if kind == b'moov':
                return seen_data
            seen_data |= kind == b'mdat'
            if size < stream.tell() - pos or pos + size > length:
                return False
            stream.seek(pos + size)
    return False


# 低码率且尾置索引的兼容 MP4 只需重封装，避免转码耗时和画质损失。
if ('mp4' in info['format']['format_name'] and video.get('codec_name') == 'h264'
        and all(s.get('codec_name') == 'aac' for s in info['streams'] if s.get('codec_type') == 'audio')
        and 8 * source.stat().st_size / duration < 1_500_000 and tail_index(source)):
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', *input_flags, '-i', str(source),
                    '-map', '0:v:0', '-map', '0:a:0?', '-c', 'copy', '-map_metadata', '-1',
                    '-movflags', '+faststart', str(output / 'mobile.mp4')], check=True)
    sys.exit(0)
master = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-INDEPENDENT-SEGMENTS']
for height, bitrate in ((480, 600), (720, 1600), (1080, 3200)):
    if height > video['height'] and len(master) > 3:
        break
    height = max(2, min(height, video['height']) // 2 * 2)
    # 每个档位按同一时间间隔强制关键帧，允许客户端从对应时间切换。
    args = ['ffmpeg', '-nostdin', '-v', 'error', '-y', *input_flags, '-threads', '2',
            '-i', str(source), '-map', '0:v:0', '-map', '0:a:0?', '-sn', '-dn', '-t', str(duration),
            '-map_metadata', '-1', '-vf', f'scale=-2:{height}', '-c:v', 'libx264',
            '-pix_fmt', 'yuv420p', '-preset', 'veryfast', '-threads', '2', '-b:v', f'{bitrate}k',
            '-maxrate', f'{bitrate}k', '-bufsize', f'{2*bitrate}k', '-sc_threshold', '0',
            '-force_key_frames', 'expr:gte(t,n_forced*4)', '-c:a', 'aac', '-b:a', '96k', '-ac', '2',
            '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod', '-hls_flags', 'independent_segments',
            '-hls_segment_filename', f'v{height}-%05d.ts', f'v{height}.m3u8']
    subprocess.run(args, cwd=output, check=True)
    playlist = output / f'v{height}.m3u8'
    if '#EXT-X-ENDLIST' not in playlist.read_text():
        raise ValueError('incomplete_video_package')
    # 根据实际片段大小计算峰值带宽，避免低估高运动画面的网络需求。
    durations = [float(line.split(':')[1].rstrip(',')) for line in playlist.read_text().splitlines()
                 if line.startswith('#EXTINF:')]
    segments = sorted(output.glob(f'v{height}-*.ts'))
    if not durations or len(segments) != len(durations):
        raise ValueError('missing_video_segments')
    peak = max(8 * p.stat().st_size / t for p, t in zip(segments, durations))
    master.extend([f'#EXT-X-STREAM-INF:BANDWIDTH={int(peak * 1.1)}', playlist.name])
(output / 'master.m3u8').write_text('\n'.join(master) + '\n')
