package com.yuyutian.mytools.connectivity.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在全部可用局域网 IPv4 接口广播 MyTools DNS-SD 服务。
 */
@Slf4j
@Service
public class MdnsAdvertisementService {

    private static final String SERVICE_TYPE = "_mytools._tcp.local.";

    private final Map<String, JmDNS> registrations = new LinkedHashMap<>();

    @Value("${mytools.connectivity.mdns-enabled:true}")
    private boolean enabled;

    @Value("${mytools.connectivity.instance-id:mytools-default}")
    private String instanceId;

    @Value("${mytools.connectivity.api-version:v1}")
    private String apiVersion;

    @Value("${server.port:23110}")
    private int port;

    /**
     * 应用启动后在非回环 IPv4 接口注册服务。
     */
    @PostConstruct
    public synchronized void start() {
        if (!enabled) {
            return;
        }
        reconcile();
        if (registrations.isEmpty()) {
            log.warn("未找到可用于局域网广播的IPv4网络接口");
        }
    }

    /**
     * 定期对齐当前可用网卡的局域网广播，适配手机热点等动态上下线场景。
     */
    @Scheduled(fixedDelayString = "${mytools.connectivity.mdns-reconcile-ms:10000}")
    public synchronized void reconcile() {
        if (!enabled) {
            return;
        }
        Map<String, InetAddress> currentAddresses = new LinkedHashMap<>();
        for (InetAddress address : localIpv4Addresses()) {
            currentAddresses.put(address.getHostAddress(), address);
        }

        // 先撤销已下线网卡的广播，避免 App 命中失效地址。
        for (String address : List.copyOf(registrations.keySet())) {
            if (!currentAddresses.containsKey(address)) {
                closeRegistration(address, registrations.remove(address));
            }
        }
        // 为新上线网卡补充广播，无需重启后端服务。
        for (Map.Entry<String, InetAddress> entry : currentAddresses.entrySet()) {
            if (!registrations.containsKey(entry.getKey())) {
                register(entry.getValue());
            }
        }
    }

    /**
     * 应用关闭时注销全部局域网广播。
     */
    @PreDestroy
    public synchronized void stop() {
        for (Map.Entry<String, JmDNS> entry : Map.copyOf(registrations).entrySet()) {
            closeRegistration(entry.getKey(), entry.getValue());
        }
        registrations.clear();
    }

    private void register(InetAddress address) {
        JmDNS registration = null;
        try {
            registration = JmDNS.create(address, instanceId);
            ServiceInfo service = ServiceInfo.create(SERVICE_TYPE, instanceId, port, 0, 0,
                    Map.of("instanceId", instanceId, "apiVersion", apiVersion, "addressFamily", "IPv4"));
            registration.registerService(service);
            registrations.put(address.getHostAddress(), registration);
            log.info("已广播局域网服务，地址：{}，端口：{}", address.getHostAddress(), port);
        } catch (IOException | RuntimeException ex) {
            if (registration != null) {
                try {
                    registration.close();
                } catch (IOException closeException) {
                    ex.addSuppressed(closeException);
                }
            }
            log.warn("在网络接口上广播局域网服务失败，地址：{}", address.getHostAddress(), ex);
        }
    }

    private void closeRegistration(String address, JmDNS registration) {
        if (registration == null) {
            return;
        }
        try {
            registration.unregisterAllServices();
            registration.close();
            log.info("已停止局域网服务广播，地址：{}", address);
        } catch (IOException ex) {
            log.warn("关闭局域网服务广播失败，地址：{}", address, ex);
        }
    }

    private List<InetAddress> localIpv4Addresses() {
        try {
            List<InetAddress> addresses = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addresses.add(address);
                    }
                }
            }
            return addresses;
        } catch (SocketException ex) {
            log.warn("读取局域网网络接口失败", ex);
            return List.of();
        }
    }
}
