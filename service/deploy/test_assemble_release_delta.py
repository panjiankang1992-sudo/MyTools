"""验证升级 Boot 依赖时增量产物仍保留加载器要求的 ZIP 存储方式。"""
from pathlib import Path
import tempfile
import unittest
import zipfile

from assemble_release_delta import assemble_one
from build_release_delta import build


class BootLibraryDeltaTest(unittest.TestCase):
    def test_replaced_and_added_libraries_are_stored_and_resources_remain_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base, target = root / 'base.jar', root / 'service.jar'
            for path, libraries in ((base, {'old.jar': b'old', 'shared.jar': b'same'}),
                                    (target, {'new.jar': b'new', 'shared.jar': b'same'})):
                with zipfile.ZipFile(path, 'w') as archive:
                    for name in ('BOOT-INF/', 'BOOT-INF/lib/', 'BOOT-INF/classes/'):
                        archive.writestr(name, b'')
                    for name, body in libraries.items():
                        archive.writestr('BOOT-INF/lib/' + name, body, compress_type=zipfile.ZIP_STORED)
                    archive.writestr('BOOT-INF/classes/application.yml', path.name.encode(), compress_type=zipfile.ZIP_DEFLATED)
            delta, apps = root / 'delta', root / 'apps'
            apps.mkdir()
            build(base, str(base), target, delta)
            result = assemble_one(delta, apps)
            self.assertTrue(result['verified'])
            with zipfile.ZipFile(apps / 'service.jar') as archive:
                self.assertNotIn('BOOT-INF/lib/old.jar', archive.namelist())
                for name in ('new.jar', 'shared.jar'):
                    self.assertEqual(archive.getinfo('BOOT-INF/lib/' + name).compress_type, zipfile.ZIP_STORED)
                self.assertEqual(archive.read('BOOT-INF/classes/application.yml'), b'service.jar')


if __name__ == '__main__':
    unittest.main()
