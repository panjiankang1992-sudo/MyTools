#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <napi/native_api.h>
#include <uv.h>
#include <vector>

namespace {
// 仅在 ArkUI 主事件循环中管理回环连接，WebSocket 未消费时不读取下一块。
struct Peer {
    uv_tcp_t socket{};
    int id{};
    napi_deferred pending{};
};
struct WriteOp {
    uv_write_t req{};
    napi_deferred done{};
    std::vector<char> data;
};
napi_env env{};
napi_ref accepted{};
uv_tcp_t *listener{};
std::map<int, Peer *> peers;
int nextId = 1;
constexpr size_t BLOCK = 65536;
struct Scope {
    napi_handle_scope scope{};
    Scope() { napi_open_handle_scope(env, &scope); }
    ~Scope() { napi_close_handle_scope(env, scope); }
};
napi_value nothing() {
    napi_value out;
    napi_get_undefined(env, &out);
    return out;
}
napi_value error(const char *text) {
    napi_value message, exception;
    napi_create_string_utf8(env, text, NAPI_AUTO_LENGTH, &message);
    napi_create_error(env, nullptr, message, &exception);
    return exception;
}
napi_value fail(const char *text) {
    napi_throw(env, error(text));
    return nullptr;
}
void closePeer(Peer *peer) {
    peers.erase(peer->id);
    uv_read_stop(reinterpret_cast<uv_stream_t *>(&peer->socket));
    if (env && peer->pending) {
        napi_reject_deferred(env, peer->pending, error("Channel closed"));
        peer->pending = nullptr;
    }
    if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(&peer->socket)))
        uv_close(reinterpret_cast<uv_handle_t *>(&peer->socket),
                 [](uv_handle_t *h) { delete static_cast<Peer *>(h->data); });
}
Peer *findPeer(napi_value value) {
    int32_t id = 0;
    if (napi_get_value_int32(env, value, &id) != napi_ok)
        return nullptr;
    auto found = peers.find(id);
    return found == peers.end() ? nullptr : found->second;
}
void onConnection(uv_stream_t *server, int status) {
    if (status < 0 || !env || server != reinterpret_cast<uv_stream_t *>(listener))
        return;
    Scope scope;
    auto *peer = new Peer{};
    if (uv_tcp_init(server->loop, &peer->socket) < 0) {
        delete peer;
        return;
    }
    peer->socket.data = peer;
    if (uv_accept(server, reinterpret_cast<uv_stream_t *>(&peer->socket)) < 0 || peers.size() >= 12) {
        closePeer(peer);
        return;
    }
    peer->id = nextId++;
    peers.emplace(peer->id, peer);
    napi_value callback, id, global;
    napi_get_reference_value(env, accepted, &callback);
    napi_create_int32(env, peer->id, &id);
    napi_get_global(env, &global);
    if (napi_call_function(env, global, callback, 1, &id, nullptr) != napi_ok)
        closePeer(peer);
}
void shutdown() {
    while (!peers.empty())
        closePeer(peers.begin()->second);
    if (listener) {
        uv_close(reinterpret_cast<uv_handle_t *>(listener),
                 [](uv_handle_t *h) { delete reinterpret_cast<uv_tcp_t *>(h); });
        listener = nullptr;
    }
    if (env && accepted)
        napi_delete_reference(env, accepted);
    accepted = nullptr;
}
napi_value start(napi_env e, napi_callback_info info) {
    if (listener)
        return fail("Channel already open");
    size_t argc = 1;
    napi_value cb;
    napi_get_cb_info(e, info, &argc, &cb, nullptr, nullptr);
    napi_valuetype type;
    if (argc != 1 || napi_typeof(e, cb, &type) != napi_ok || type != napi_function)
        return fail("Invalid callback");
    uv_loop_t *loop = nullptr;
    napi_get_uv_event_loop(e, &loop);
    listener = new uv_tcp_t{};
    if (!loop || uv_tcp_init(loop, listener) < 0) {
        delete listener;
        listener = nullptr;
        return fail("Socket initialization failed");
    }
    sockaddr_in address{};
    uv_ip4_addr("127.0.0.1", 0, &address);
    int size = sizeof(address);
    if (uv_tcp_bind(listener, reinterpret_cast<sockaddr *>(&address), 0) < 0 ||
        uv_listen(reinterpret_cast<uv_stream_t *>(listener), 16, onConnection) < 0 ||
        uv_tcp_getsockname(listener, reinterpret_cast<sockaddr *>(&address), &size) < 0 ||
        napi_create_reference(e, cb, 1, &accepted) != napi_ok) {
        shutdown();
        return fail("Loopback unavailable");
    }
    napi_value port;
    napi_create_int32(e, ntohs(address.sin_port), &port);
    return port;
}
napi_value read(napi_env e, napi_callback_info info) {
    size_t argc = 1;
    napi_value id, promise;
    napi_get_cb_info(e, info, &argc, &id, nullptr, nullptr);
    auto *peer = argc == 1 ? findPeer(id) : nullptr;
    if (!peer || peer->pending)
        return fail("Invalid read");
    napi_create_promise(e, &peer->pending, &promise);
    int rc = uv_read_start(
        reinterpret_cast<uv_stream_t *>(&peer->socket),
        [](uv_handle_t *, size_t, uv_buf_t *b) {
            b->base = static_cast<char *>(std::malloc(BLOCK));
            b->len = b->base ? BLOCK : 0;
        },
        [](uv_stream_t *s, ssize_t n, const uv_buf_t *b) {
            std::unique_ptr<char, decltype(&std::free)> allocation(b->base, std::free);
            if (!env || !n)
                return;
            Scope scope;
            auto *peer = static_cast<Peer *>(s->data);
            uv_read_stop(s);
            if (!peer->pending)
                return;
            auto pending = peer->pending;
            peer->pending = nullptr;
            if (n < 0 && n != UV_EOF) {
                napi_reject_deferred(env, pending, error("Local read failed"));
                return;
            }
            napi_value buf;
            void *destination = nullptr;
            if (napi_create_arraybuffer(env, n > 0 ? static_cast<size_t>(n) : 0, &destination, &buf) !=
                napi_ok) {
                napi_reject_deferred(env, pending, error("Buffer unavailable"));
                return;
            }
            if (n > 0)
                std::memcpy(destination, b->base, static_cast<size_t>(n));
            napi_resolve_deferred(env, pending, buf);
        });
    if (rc < 0) {
        napi_reject_deferred(e, peer->pending, error("Read unavailable"));
        peer->pending = nullptr;
    }
    return promise;
}
napi_value write(napi_env e, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2], promise;
    napi_get_cb_info(e, info, &argc, args, nullptr, nullptr);
    auto *peer = argc == 2 ? findPeer(args[0]) : nullptr;
    size_t length = 0;
    void *bytes = nullptr;
    if (!peer || napi_get_arraybuffer_info(e, args[1], &bytes, &length) != napi_ok || length > BLOCK ||
        !length)
        return fail("Invalid write");
    auto op = std::make_unique<WriteOp>();
    op->data.assign(static_cast<char *>(bytes), static_cast<char *>(bytes) + length);
    napi_create_promise(e, &op->done, &promise);
    auto b = uv_buf_init(op->data.data(), static_cast<unsigned>(length));
    op->req.data = op.get();
    int rc = uv_write(&op->req, reinterpret_cast<uv_stream_t *>(&peer->socket), &b, 1,
                      [](uv_write_t *r, int code) {
                          std::unique_ptr<WriteOp> op(static_cast<WriteOp *>(r->data));
                          if (!env)
                              return;
                          Scope scope;
                          if (code < 0)
                              napi_reject_deferred(env, op->done, error("Local write failed"));
                          else
                              napi_resolve_deferred(env, op->done, nothing());
                      });
    if (rc < 0)
        napi_reject_deferred(e, op->done, error("Write unavailable"));
    else
        op.release();
    return promise;
}
napi_value end(napi_env e, napi_callback_info info) {
    size_t argc = 1;
    napi_value id;
    napi_get_cb_info(e, info, &argc, &id, nullptr, nullptr);
    if (argc == 1) {
        auto *peer = findPeer(id);
        if (peer)
            closePeer(peer);
    }
    return nothing();
}
napi_value stop(napi_env, napi_callback_info) {
    shutdown();
    return nothing();
}
napi_value init(napi_env e, napi_value exports) {
    env = e;
    napi_add_env_cleanup_hook(
        e,
        [](void *) {
            env = nullptr;
            shutdown();
        },
        nullptr);
    napi_property_descriptor props[] = {
        {"start", nullptr, start, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"read", nullptr, read, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"write", nullptr, write, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"end", nullptr, end, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"stop", nullptr, stop, nullptr, nullptr, nullptr, napi_default, nullptr}};
    napi_define_properties(e, exports, 5, props);
    return exports;
}
napi_module module = {1, 0, nullptr, init, "home_channel", nullptr, {0}};
} // namespace
extern "C" __attribute__((constructor)) void RegisterHomeChannel() { napi_module_register(&module); }
