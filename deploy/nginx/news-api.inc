# 在现有 MyTools 的 server 块内引入，保留其他页面和 API 的转发配置。
# 不带 URI 的 proxy_pass 保留完整路径，由 Gateway 执行登录和 owner 校验。
location ^~ /api/v1/news/ {
    proxy_pass http://127.0.0.1:23200;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 35s;
    proxy_no_cache 1;
    proxy_cache_bypass 1;
}
