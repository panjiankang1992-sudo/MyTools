"""在已登录且已打开多张图片的模拟器上验证控件命中、缩放、切图和返回位置。"""
import argparse
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', required=True)
    parser.add_argument('--hdc', default='/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc')
    args = parser.parse_args()
    hdc = [args.hdc, '-t', args.target]

    def command(text):
        result = subprocess.run(hdc + ['shell', text], capture_output=True, text=True, timeout=20, check=True)
        assert '[Fail]' not in result.stdout, 'Device command failed'
        return result.stdout

    with tempfile.TemporaryDirectory(prefix='mytools-image-gestures-') as directory:
        count = 0

        def snapshot():
            nonlocal count
            count += 1
            remote = '/data/local/tmp/mytools-image-gesture-acceptance.json'
            command('uitest dumpLayout -p ' + remote)
            local = Path(directory) / f'{count}.json'
            subprocess.run(hdc + ['file', 'recv', remote, str(local)], capture_output=True, check=True, timeout=20)
            root = json.loads(local.read_text())
            nodes = []

            def walk(node):
                attributes = node.get('attributes', {})
                attributes['box'] = [int(value) for value in re.findall(r'\d+', attributes.get('bounds', ''))]
                nodes.append(attributes)
                for child in node.get('children', []):
                    walk(child)

            walk(root)
            return nodes

        def preview(nodes):
            return next((node for node in nodes if node.get('id', '').startswith('galleryPreview-')), None)

        def has_controls(nodes):
            return any(node.get('id') == 'imageResetZoom' for node in nodes)

        nodes = snapshot()
        current = preview(nodes)
        assert current is not None, 'Open an image viewer with at least two images before running this test'
        left, top, right, bottom = current['box']
        center_x, center_y = (left + right) // 2, (top + bottom) // 2
        from_x, to_x = int(left + (right - left) * .82), int(left + (right - left) * .22)

        def show_controls():
            result = snapshot()
            if not has_controls(result):
                command(f'uitest uiInput click {center_x} {center_y}')
                time.sleep(.35)
                result = snapshot()
            assert has_controls(result), 'The center image tap did not show viewer controls'
            return result

        def horizontal():
            command(f'uitest uiInput swipe {from_x} {center_y} {to_x} {center_y} 900')
            time.sleep(.7)
            return snapshot()

        nodes = show_controls()
        first = preview(nodes)['id']
        after = horizontal()
        assert preview(after) and preview(after)['id'] != first, 'Visible controls blocked horizontal navigation'
        time.sleep(2.8)
        before = snapshot()
        assert not has_controls(before), 'Viewer controls did not auto-hide'
        after = horizontal()
        assert preview(after) and preview(after)['id'] != preview(before)['id'], 'Hidden-controls navigation failed'

        show_controls()
        command(f'uitest uiInput doubleClick {center_x} {center_y}')
        time.sleep(.35)
        zoomed = show_controls()
        assert any(node.get('text') == '2×' for node in zoomed), 'Double tap did not zoom the image'
        after = horizontal()
        assert preview(after)['id'] == preview(zoomed)['id'], 'Dragging a zoomed image changed the current image'
        nodes = show_controls()
        reset = next(node for node in nodes if node.get('id') == 'imageResetZoom')
        x1, y1, x2, y2 = reset['box']
        command(f'uitest uiInput click {(x1 + x2) // 2} {(y1 + y2) // 2}')
        time.sleep(.2)
        before = snapshot()
        assert any(node.get('text') == '1×' for node in before), 'Reset zoom control did not receive its tap'
        command(f'uitest uiInput swipe {center_x} {int(top + (bottom - top) * .73)} '
                f'{center_x} {int(top + (bottom - top) * .33)} 900')
        time.sleep(.7)
        after = snapshot()
        assert preview(after) and preview(after)['id'] != preview(before)['id'], 'Vertical image navigation failed'
        selected = preview(after)['id'].removeprefix('galleryPreview-')
        command('uitest uiInput keyEvent Back')
        time.sleep(.7)
        after = snapshot()
        assert not preview(after), 'Back did not close the image viewer'
        assert any(node.get('id') == 'galleryCard-' + selected for node in after), 'Back did not reveal the current image'
        print('Image viewer visible/hidden controls, zoomed drag, vertical navigation and return-position acceptance passed')


if __name__ == '__main__':
    main()
