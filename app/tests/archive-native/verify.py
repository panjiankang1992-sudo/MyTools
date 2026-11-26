"""生成合成压缩包，验证原生引擎，退出时删除全部测试文件。"""
import gzip
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import zipfile

engine, encoder = sys.argv[1:3]
count = 0

def check(value, message):
    global count
    assert value, message
    count += 1

with tempfile.TemporaryDirectory(prefix='mytools-archive-tests-') as folder:
    root = Path(folder)
    payload = root / 'payload.txt'
    payload.write_text('Archive roundtrip 测试\n', encoding='utf8')
    def run(path, password='', target=None, ids=(0,), cancelled=False, cancel_during=False):
        args = [engine, str(path), password]
        if target is not None:
            args += [str(target), *map(str, ids)]
        env = dict(os.environ)
        if cancelled:
            env['ARCHIVE_TEST_CANCEL'] = '1'
        if cancel_during:
            env['ARCHIVE_TEST_CANCEL_DURING'] = '1'
        return json.loads(subprocess.check_output(args, env=env, text=True, timeout=60))
    for name, flags in [('plain.zip',['-tzip']),('crypto.zip',['-tzip','-mem=ZipCrypto']),
                        ('aes.zip',['-tzip','-mem=AES256']),('headers.7z',['-t7z','-mhe=on'])]:
        archive = root / name
        encrypted = name != 'plain.zip'
        password = (' Space-Test-2026 ' if name.endswith('.zip') else ' 密码 Test-2026 ') if encrypted else ''
        subprocess.run([encoder,'a',str(archive),str(payload),*flags,*(['-p'+password] if encrypted else [])],check=True,stdout=subprocess.DEVNULL)
        info = run(archive,password)
        check(info['code']=='', name+' inspect')
        target = root / (name+'-out')
        value = run(archive,password,target)
        check(value['code']=='',name+' extract')
        check((target/'payload.txt').read_bytes()==payload.read_bytes(),name+' roundtrip')
        if encrypted:
            failed = root/(name+'-wrong')
            check(run(archive,'wrong',failed)['code'] in ['ARCHIVE_003','ARCHIVE_004'], name+' wrong password')
            check(not failed.exists(),name+' cleanup failure')
        check(run(archive,password,cancelled=True)['code']=='ARCHIVE_010',name+' cancellation')
        check(run(archive,password,target)['code']=='ARCHIVE_008',name+' reject existing output')
        check((target/'payload.txt').read_bytes()==payload.read_bytes(),name+' preserve existing output')
    # 同一 ZIP 通过两次增量添加形成不同条目使用不同密码的真实样本。
    first = root/'first.txt'; first.write_bytes(b'first encrypted entry')
    second = root/'second.txt'; second.write_bytes(b'second encrypted entry')
    mixed = root/'mixed.zip'
    for file, secret in [(first, 'First-Test'), (second, 'Second-Test')]:
        subprocess.run([encoder,'a',str(mixed),str(file),'-tzip','-mem=AES256','-p'+secret],check=True,stdout=subprocess.DEVNULL)
    info = run(mixed)
    check(info['code']=='', 'mixed ZIP visible directory')
    for entry in info['entries']:
        secret = 'First-Test' if entry['path']=='first.txt' else 'Second-Test'
        target = root/('mixed-'+str(entry['id']))
        check(run(mixed,secret,target,(entry['id'],))['code']=='', 'mixed ZIP correct entry password')
        check((target/entry['path']).read_bytes()==(root/entry['path']).read_bytes(), 'mixed ZIP byte equality')
        wrong = root/('mixed-wrong-'+str(entry['id']))
        check(run(mixed,'Wrong-Test',wrong,(entry['id'],))['code'] in ['ARCHIVE_003','ARCHIVE_004'], 'mixed ZIP wrong entry password')
        check(not wrong.exists(), 'mixed ZIP failed entry cleanup')
    # 目录与文件占用相同路径时不得发布部分结果。
    with zipfile.ZipFile(root/'parent-conflict.zip','w') as z:
        z.writestr('parent',b'file'); z.writestr('parent/child',b'child')
    conflict=root/'conflict-output'
    check(run(root/'parent-conflict.zip','',conflict,(0,1))['code']!='', 'parent file conflict rejected')
    check(not conflict.exists(), 'parent conflict cleanup')
    with zipfile.ZipFile(root/'cancel-running.zip','w',compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('large.bin', b'abcd'*(16*1024*1024))
    interrupted=root/'interrupted-output'
    check(run(root/'cancel-running.zip','',interrupted,cancel_during=True)['code']=='ARCHIVE_010', 'cancel running decoder')
    check(not interrupted.exists(), 'running cancellation removes partial output')
    for name in ['../escape','/absolute','C:/drive','a/../../escape','bad\x01name']:
        archive=root/'unsafe.zip'
        with zipfile.ZipFile(archive,'w') as z:z.writestr(name,b'bad')
        check(run(archive)['code']=='ARCHIVE_009','path safety '+repr(name))
    with zipfile.ZipFile(root/'duplicate.zip','w') as z:
        z.writestr('Case.txt',b'a');z.writestr('case.txt',b'b')
    check(run(root/'duplicate.zip')['code']=='ARCHIVE_009','case collision')
    with zipfile.ZipFile(root/'link.zip','w') as z:
        entry=zipfile.ZipInfo('link');entry.create_system=3;entry.external_attr=0o120777 << 16;z.writestr(entry,b'../../escape')
    check(run(root/'link.zip')['code']=='ARCHIVE_009','symbolic link')
    with zipfile.ZipFile(root/'many.zip','w') as z:
        for i in range(5001):z.writestr(str(i),b'')
    check(run(root/'many.zip')['code']=='ARCHIVE_006','entry quota')
    # 中央目录与本地文件头声明虚假的展开大小，预检必须拒绝。
    with zipfile.ZipFile(root/'size.zip','w') as z:z.writestr('large',b'x')
    data=bytearray((root/'size.zip').read_bytes());central=data.index(b'PK\x01\x02')
    data[22:26]=(301*1024*1024).to_bytes(4,'little');data[central+24:central+28]=(301*1024*1024).to_bytes(4,'little');(root/'size.zip').write_bytes(data)
    check(run(root/'size.zip')['code']=='ARCHIVE_006','expanded quota')
    with tarfile.open(root/'plain.tar','w') as t:t.add(payload,arcname='folder/payload.txt')
    check(run(root/'plain.tar','','%s/tar-out'%root)['code']=='','TAR extract')
    with gzip.open(root/'plain.gz','wb') as g:g.write(payload.read_bytes())
    check(run(root/'plain.gz','','%s/gz-out'%root)['code']=='','GZIP extract')
    with tarfile.open(root/'link.tar','w') as t:
        link=tarfile.TarInfo('badlink');link.type=tarfile.SYMTYPE;link.linkname='../../escape';t.addfile(link)
    check(run(root/'link.tar')['code']=='ARCHIVE_009','TAR symbolic link')
    print(json.dumps({'passed':count,'temporary_files_removed_on_exit':True}))
