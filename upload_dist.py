import paramiko
import os

host = '192.168.1.9'
port = 22
username = 'pankang'
password = 'pankang/1015'

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

try:
    print(f"Connecting to {host}...")
    ssh.connect(host, port=port, username=username, password=password)
    print("Connected!")

    sftp = ssh.open_sftp()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    dist_dir = os.path.join(script_dir, 'webapp', 'dist')
    remote_webapp_dir = '/opt/mycode/MyTools/webapp'

    print("Uploading frontend dist...")

    for root, dirs, files in os.walk(dist_dir):
        for file in files:
            local_path = os.path.join(root, file)
            relative_path = os.path.relpath(local_path, dist_dir)
            remote_path = remote_webapp_dir + '/' + relative_path.replace('\\', '/')

            remote_dir = os.path.dirname(remote_path)
            try:
                sftp.stat(remote_dir)
            except:
                ssh.exec_command(f"mkdir -p {remote_dir}")

            print(f"  {relative_path}")
            sftp.put(local_path, remote_path)

    sftp.close()
    print("Done!")
except Exception as e:
    print(f"Error: {e}")