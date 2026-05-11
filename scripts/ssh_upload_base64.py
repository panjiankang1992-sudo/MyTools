#!/usr/bin/env python3
"""
Upload file via SSH using base64 encoding
"""
import sys
import paramiko
import base64

def upload_file_via_ssh(host, port, user, password, local_file, remote_path):
    try:
        print(f"Connecting to {host}...")
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        client.connect(host, port=port, username=user, password=password, timeout=60)

        # Read and encode file
        print(f"Reading {local_file}...")
        with open(local_file, 'rb') as f:
            data = f.read()
        encoded = base64.b64encode(data).decode('ascii')
        print(f"File size: {len(data)} bytes, Base64 size: {len(encoded)} bytes")

        # Create empty file on server
        print(f"Creating {remote_path} on server...")
        stdin, stdout, stderr = client.exec_command(f"truncate -s 0 {remote_path}")
        stderr.read().decode('utf-8')

        # Write in chunks (split by newlines every 1024 chars for safety)
        chunk_size = 768  # ~1KB base64 per line
        print(f"Uploading in chunks of {chunk_size} chars...")
        total_chunks = (len(encoded) + chunk_size - 1) // chunk_size

        for i in range(0, len(encoded), chunk_size):
            chunk = encoded[i:i + chunk_size]
            cmd = f"echo '{chunk}' >> {remote_path}"
            stdin, stdout, stderr = client.exec_command(cmd)
            stderr.read()
            progress = (i + chunk_size) * 100 // len(encoded)
            print(f"\rProgress: {progress}%", end='', flush=True)

        print(f"\nDecoding file on server...")
        # Decode and verify
        stdin, stdout, stderr = client.exec_command(f"base64 -d {remote_path} > {remote_path}.tmp && mv {remote_path}.tmp {remote_path}")
        stderr_output = stderr.read().decode('utf-8')
        if stderr_output:
            print(f"Decode stderr: {stderr_output}")

        # Verify size
        stdin, stdout, stderr = client.exec_command(f"wc -c < {remote_path}")
        remote_size = int(stdout.read().decode('utf-8').strip())
        print(f"Remote file size: {remote_size} bytes")

        if remote_size != len(data):
            print(f"WARNING: Size mismatch! Local={len(data)}, Remote={remote_size}")
            return 1

        # Extract
        print("Extracting archive...")
        stdin, stdout, stderr = client.exec_command(f"cd /home/pankang && tar -xf {remote_path}")
        stderr_output = stderr.read().decode('utf-8')
        if stderr_output:
            print(f"Extract stderr: {stderr_output}")

        # Copy to nginx directory
        print("Copying to nginx html directory...")
        stdin, stdout, stderr = client.exec_command("cp -r /home/pankang/dist/* /var/www/html/ 2>/dev/null || mkdir -p /var/www/html && cp -r /home/pankang/dist/* /var/www/html/")
        stderr_output = stderr.read().decode('utf-8')
        if stderr_output:
            print(f"Nginx copy stderr: {stderr_output}")

        print("Upload complete!")
        client.close()
        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    if len(sys.argv) < 6:
        print("Usage: ssh_upload_base64.py <host> <port> <user> <password> <local_file> <remote_path>")
        sys.exit(1)

    host = sys.argv[1]
    port = int(sys.argv[2])
    user = sys.argv[3]
    password = sys.argv[4]
    local_file = sys.argv[5]
    remote_path = sys.argv[6]

    sys.exit(upload_file_via_ssh(host, port, user, password, local_file, remote_path))
