#!/usr/bin/env python3
"""Compare bounded public-network probes without changing firewall or application data."""
import json
import socket
import subprocess

TARGETS = [('cloudflare', '1.1.1.1', 443), ('alidns', '223.5.5.5', 443), ('baidu', 'www.baidu.com', 443)]


def main():
    probes = []
    for label, host, port in TARGETS:
        try:
            address = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
            probes.append((label, address, port))
        except OSError:
            pass
    code = "import socket,json;out={};\nfor name,host,port in " + repr(probes) + ":\n try:\n  c=socket.create_connection((host,port),2);c.close();out[name]=True\n except OSError:out[name]=False\nprint(json.dumps(out))"
    report = {'host': json.loads(subprocess.check_output(['/usr/bin/python3', '-c', code], timeout=10))}
    for name in ('mytools-reader-runtime', 'mytools-adaptation-runtime-prod'):
        info = json.loads(subprocess.check_output(['docker', 'container', 'inspect', name]))[0]
        report[name] = json.loads(subprocess.check_output(['nsenter', '-t', str(info['State']['Pid']), '-n', '/usr/bin/python3', '-c', code], timeout=10))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
