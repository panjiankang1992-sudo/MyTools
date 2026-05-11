#!/usr/bin/env python3
"""
Upload file via SFTP with proper path handling
"""
import sys
import paramiko
import os

def upload_file_sftp(host, port, user, password, local_file, remote_dir):
    try:
        print(f"Connecting to {host}...")
        transport = paramiko.Transport((host, port))
        transport.connect(username=user, password=password)
        sftp = paramiko.SFTPClient.from_transport(transport)

        local_path = os.path.abspath(local_file)
        filename = os.path.basename(local_file)
        remote_path = remote_dir.rstrip('/') + '/' + filename

        print(f"Uploading: {local_path} -> {remote_path}")
        sftp.put(local_path, remote_path)

        # Get remote file size
        remote_size = sftp.stat(remote_path).st_size
        local_size = os.path.getsize(local_path)

        print(f"Local size: {local_size}, Remote size: {remote_size}")

        if remote_size == local_size:
            print("Upload complete and verified!")
        else:
            print("WARNING: Size mismatch!")

        sftp.close()
        transport.close()
        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    if len(sys.argv) < 6:
        print("Usage: ssh_upload_sftp.py <host> <port> <user> <password> <local_file> <remote_dir>")
        sys.exit(1)

    host = sys.argv[1]
    port = int(sys.argv[2])
    user = sys.argv[3]
    password = sys.argv[4]
    local_file = sys.argv[5]
    remote_dir = sys.argv[6]

    sys.exit(upload_file_sftp(host, port, user, password, local_file, remote_dir))
