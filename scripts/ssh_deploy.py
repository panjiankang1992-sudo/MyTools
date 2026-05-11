#!/usr/bin/env python3
"""
SSH deployment script using paramiko
"""
import sys
import paramiko

def ssh_command(host, port, user, password, command):
    try:
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        client.connect(host, port=port, username=user, password=password, timeout=10)

        stdin, stdout, stderr = client.exec_command(command)
        output = stdout.read().decode('utf-8')
        error = stderr.read().decode('utf-8')

        if error:
            print(f"STDERR: {error}", file=sys.stderr)
        print(output)

        client.close()
        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    if len(sys.argv) < 5:
        print("Usage: ssh_deploy.py <host> <port> <user> <password> <command>")
        sys.exit(1)

    host = sys.argv[1]
    port = int(sys.argv[2])
    user = sys.argv[3]
    password = sys.argv[4]
    command = ' '.join(sys.argv[5:])

    sys.exit(ssh_command(host, port, user, password, command))
