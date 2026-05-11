#!/usr/bin/env python3
import subprocess
import paramiko

# Generate hash locally
result = subprocess.run(['python', '-c', 'import bcrypt; print(bcrypt.hashpw(b"admin123", bcrypt.gensalt()).decode())'], capture_output=True, text=True)
hashed = result.stdout.strip()
print(f'Generated hash: {hashed}')

# SSH to server
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('192.168.1.9', port=22, username='pankang', password='pankang/1015')

# Delete old user
stdin, stdout, stderr = client.exec_command('docker exec mysql mysql -u root -p"YUyutian/1015" my_tools -e "DELETE FROM t_user WHERE id = 1;" 2>&1')
print('Delete:', stdout.read().decode('utf-8', errors='replace'))

# Write SQL to file on server
sql = f"INSERT INTO t_user (id, username, password, nickname, role) VALUES (1, 'admin', '{hashed}', 'Administrator', 'ADMIN');"
sftp = client.open_sftp()
with sftp.file('/tmp/insert_user.sql', 'w') as f:
    f.write(sql)
sftp.close()

# Execute SQL
stdin, stdout, stderr = client.exec_command('docker exec -i mysql mysql -u root -p"YUyutian/1015" my_tools < /tmp/insert_user.sql 2>&1')
print('Insert:', stdout.read().decode('utf-8', errors='replace'))
print(stderr.read().decode('utf-8', errors='replace'))

# Verify
stdin, stdout, stderr = client.exec_command('docker exec mysql mysql -u root -p"YUyutian/1015" my_tools -e "SELECT id, username, LENGTH(password) as pwd_len FROM t_user;" 2>&1')
print('Verify:', stdout.read().decode('utf-8', errors='replace'))

client.close()
print('Done!')
