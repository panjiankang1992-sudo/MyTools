#!/usr/bin/env python3
"""
SCP upload using paramiko
"""
import sys
import paramiko
from pathlib import Path

def scp_upload(host, port, user, password, local_file, remote_path):
    try:
        transport = paramiko.Transport((host, port))
        transport.connect(username=user, password=password)
        sftp = paramiko.SFTPClient.from_transport(transport)

        print(f"Uploading: {local_file} -> {remote_path}")
        sftp.put(local_file, remote_path)
        print(f"Upload complete!")

        sftp.close()
        transport.close()
        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    if len(sys.argv) < 6:
        print("Usage: scp_upload.py <host> <port> <user> <password> <local_file> <remote_path>")
        sys.exit(1)

    host = sys.argv[1]
    port = int(sys.argv[2])
    user = sys.argv[3]
    password = sys.argv[4]
    local_file = sys.argv[5]
    remote_path = sys.argv[6]

    sys.exit(scp_upload(host, port, user, password, local_file, remote_path))
