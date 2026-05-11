import paramiko
import os
import time
import re
import hashlib

host = '192.168.1.9'
port = 22
username = 'pankang'
password = 'pankang/1015'

script_dir = os.path.dirname(os.path.abspath(__file__))

def run_ssh_command(ssh, command):
    """执行 SSH 命令并等待完成"""
    stdin, stdout, stderr = ssh.exec_command(command)
    stdout.channel.recv_exit_status()
    return stdout.read().decode(), stderr.read().decode()

def deploy():
    """部署到远程服务器（需先手动构建）"""
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

    try:
        print(f"Connecting to {host}...")
        ssh.connect(host, port=port, username=username, password=password)
        print("Connected!")

        sftp = ssh.open_sftp()

        # 1. 部署前端到 /opt/mycode/MyTools/webapp
        print("Deploying frontend...")
        dist_dir = os.path.join(script_dir, 'webapp', 'dist')
        remote_webapp_dir = '/opt/mycode/MyTools/webapp'

        if not os.path.exists(dist_dir):
            print("ERROR: Frontend not built! Please run 'cd webapp && npm run build' first.")
            return

        # 清理远程 webapp 目录
        run_ssh_command(ssh, f"rm -rf {remote_webapp_dir}/*")
        run_ssh_command(ssh, f"mkdir -p {remote_webapp_dir}")

        # 生成缓存破坏参数（基于内容hash + 时间戳）
        cache_bust = hashlib.md5(str(time.time()).encode()).hexdigest()[:8]

        # 修改 index.html 添加缓存破坏参数
        index_local = os.path.join(dist_dir, 'index.html')
        if os.path.exists(index_local):
            with open(index_local, 'r', encoding='utf-8') as f:
                content = f.read()
            # 给所有静态资源引用添加 ?v= 参数
            content = re.sub(r'(src|href)="/(assets/[^"]+\.(js|css))"', rf'\1="/\2?v={cache_bust}"', content)
            content = re.sub(r'(src|href)="/(favicon\.[^"]+)"', rf'\1="/\2?v={cache_bust}"', content)
            with open(index_local, 'w', encoding='utf-8') as f:
                f.write(content)
            print(f"  Added cache-bust: ?v={cache_bust}")

        # 上传前端文件
        file_count = 0
        for root, dirs, files in os.walk(dist_dir):
            for file in files:
                local_path = os.path.join(root, file)
                relative_path = os.path.relpath(local_path, dist_dir)
                remote_path = f"{remote_webapp_dir}/{relative_path.replace(os.sep, '/')}"

                remote_dir = os.path.dirname(remote_path)
                try:
                    sftp.stat(remote_dir)
                except:
                    run_ssh_command(ssh, f"mkdir -p {remote_dir}")

                print(f"  [FE] {relative_path}")
                sftp.put(local_path, remote_path)
                file_count += 1

        print(f"  Uploaded {file_count} frontend files")

        # 2. 部署后端到 /opt/mycode/MyTools/backend
        print("Deploying backend...")
        backend_jar = os.path.join(script_dir, 'target', 'mytools-1.0.0.jar')
        remote_backend_dir = '/opt/mycode/MyTools/backend'

        if not os.path.exists(backend_jar):
            print("ERROR: Backend not built! Please run 'mvn clean package -DskipTests' first.")
            return

        # 创建远程目录
        run_ssh_command(ssh, f"rm -rf {remote_backend_dir}/*")
        run_ssh_command(ssh, f"mkdir -p {remote_backend_dir}")

        # 上传 jar 文件
        print(f"  [BE] mytools-1.0.0.jar")
        sftp.put(backend_jar, f"{remote_backend_dir}/mytools-1.0.0.jar")

        sftp.close()

        # 3. 配置并启动后端服务 (systemd)
        print("Configuring backend service...")

        backend_service = f"""[Unit]
Description=MyTools Backend Service
After=network.target

[Service]
Type=simple
User=pankang
WorkingDirectory={remote_backend_dir}
ExecStart=/usr/bin/java -jar {remote_backend_dir}/mytools-1.0.0.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
"""

        # 写入 systemd 服务文件
        run_ssh_command(ssh, f'cat > /tmp/mytools-backend.service << "SERVICEEOF"\n{backend_service}SERVICEEOF')

        # 复制并启用服务
        run_ssh_command(ssh, 'sudo cp /tmp/mytools-backend.service /etc/systemd/system/')
        run_ssh_command(ssh, 'sudo systemctl daemon-reload')
        run_ssh_command(ssh, 'sudo systemctl enable mytools-backend')
        run_ssh_command(ssh, 'sudo systemctl stop mytools-backend 2>/dev/null || true')
        run_ssh_command(ssh, 'sudo systemctl start mytools-backend')

        # 4. 配置并启动 Nginx (端口 29211)
        print("Configuring Nginx...")

        nginx_config = f"""server {{
    listen 29211;
    server_name _;

    root {remote_webapp_dir};
    index index.html;

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

    location / {{
        try_files $uri $uri/ /index.html;
    }}

    location /api {{
        proxy_pass http://127.0.0.1:29210;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }}
}}
"""

        run_ssh_command(ssh, f'cat > /tmp/mytools-frontend.conf << "NGINXEOF"\n{nginx_config}NGINXEOF')

        # 配置 Nginx
        run_ssh_command(ssh, 'sudo cp /tmp/mytools-frontend.conf /etc/nginx/sites-available/mytools')
        run_ssh_command(ssh, 'sudo ln -sf /etc/nginx/sites-available/mytools /etc/nginx/sites-enabled/mytools')
        run_ssh_command(ssh, 'sudo rm -f /etc/nginx/sites-enabled/default')
        run_ssh_command(ssh, 'sudo nginx -t')
        run_ssh_command(ssh, 'sudo systemctl enable nginx')
        run_ssh_command(ssh, 'sudo systemctl stop nginx 2>/dev/null || true')
        run_ssh_command(ssh, 'sudo systemctl start nginx')

        # 等待服务启动
        print("Waiting for services to start...")
        time.sleep(5)

        # 验证部署
        print("\n=== Deployment Verification ===")

        stdout, _ = run_ssh_command(ssh, 'sudo systemctl status mytools-backend | head -5')
        print(f"Backend status:\n{stdout}")

        stdout, _ = run_ssh_command(ssh, 'sudo netstat -tlnp | grep -E "29210|29211"')
        print(f"Ports:\n{stdout}")

        ssh.close()
        print("\nDeployment complete!")
        print("Frontend: http://192.168.1.9:29211")
        print("Backend API: http://192.168.1.9:29210")

    except Exception as e:
        print(f"Error: {e}")
        raise

if __name__ == '__main__':
    print("=" * 50)
    print("MyTools 部署脚本")
    print("=" * 50)
    print("\n注意：部署前请先构建项目：")
    print("  1. cd webapp && npm run build")
    print("  2. mvn clean package -DskipTests")
    print("")
    print("Starting deployment...\n")

    deploy()
