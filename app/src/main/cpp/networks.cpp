
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <android/looper.h>
#include <android/log.h>
#include "networks.h"
#include "native-lib.h"

#define LOGIF(X, ...) __android_log_print(ANDROID_LOG_INFO, "NetworksJNI", X, ##__VA_ARGS__)

static jmethodID jni_onAdd;
static jmethodID jni_onRemove;

static int netlink_socket() {
    int sock = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE);
    if (sock < 0)
        return -1;

    struct sockaddr_nl addr;
    memset(&addr, 0, sizeof(addr));
    addr.nl_family = AF_NETLINK;
    addr.nl_groups = RTMGRP_IPV4_IFADDR;

    if (bind(sock, (struct sockaddr *) &addr, sizeof(addr)) == -1){
        close(sock);
        return -1;
    }

    return sock;
}

static int request_addresses(int fd)
{
    struct {
        struct nlmsghdr n;
        struct ifaddrmsg r;
        uint8_t alignment_padding[64];
    } req;

    memset(&req, 0, sizeof(req));
    req.n.nlmsg_len = NLMSG_LENGTH(sizeof(struct ifaddrmsg));
    req.n.nlmsg_flags = NLM_F_REQUEST | NLM_F_ROOT;
    req.n.nlmsg_type = RTM_GETADDR;
    req.r.ifa_family = AF_INET;
    struct rtattr *rta = (struct rtattr *)(((char *)&req) + NLMSG_ALIGN(req.n.nlmsg_len));
    rta->rta_len = RTA_LENGTH(4);

    if (send(fd, &req, req.n.nlmsg_len, 0)<0)
        return -1;

    return 0;
}

static void read_addresses(JNIEnv *env, jobject object, int fd){
    uint8_t buff[4096];
    ssize_t len = recv(fd, buff, sizeof buff, 0);
    if (len<=0)
        return;

    struct nlmsghdr *nlh = (struct nlmsghdr *)buff;
    for (nlh = (struct nlmsghdr *)buff; (NLMSG_OK (nlh, (size_t)len)) && (nlh->nlmsg_type != NLMSG_DONE); nlh = NLMSG_NEXT(nlh, len)) {

        switch (nlh->nlmsg_type) {
            case RTM_NEWADDR:
            case RTM_DELADDR: {
                struct ifaddrmsg *ifa = (struct ifaddrmsg *) NLMSG_DATA(nlh);

                // ignore loopback networkLinks
                if (ifa->ifa_scope == RT_SCOPE_HOST || ifa->ifa_family != AF_INET)
                    continue;

                struct rtattr *rth = IFA_RTA(ifa);
                int rtl = IFA_PAYLOAD(nlh);

                // ifa->ifa_family;
                jstring name = NULL;
                jbyteArray addr_bytes = NULL;
                jbyteArray broadcast_bytes = NULL;

                for (; rtl && RTA_OK(rth, rtl); rth = RTA_NEXT(rth, rtl)) {
                    void *data = RTA_DATA(rth);
                    unsigned int len = RTA_PAYLOAD(rth);

                    switch (rth->rta_type) {
                        case IFA_LOCAL:
                            addr_bytes = env->NewByteArray(len);
                            env->SetByteArrayRegion(addr_bytes, 0, len, (const jbyte *) data);
                            break;
                        case IFA_LABEL:
                            name = env->NewStringUTF((const char *)data);
                            break;
                        case IFA_BROADCAST:
                            broadcast_bytes = env->NewByteArray(len);
                            env->SetByteArrayRegion(broadcast_bytes, 0, len, (const jbyte *) data);
                            break;
                    }
                }
                if (!name)
                    continue;

                env->CallVoidMethod(object,
                                    nlh->nlmsg_type == RTM_NEWADDR ? jni_onAdd: jni_onRemove,
                                    name, addr_bytes, broadcast_bytes, ifa->ifa_prefixlen);
                if (name)
                    env->DeleteLocalRef(name);
                if (addr_bytes)
                    env->DeleteLocalRef(addr_bytes);
                if (broadcast_bytes)
                    env->DeleteLocalRef(broadcast_bytes);

                if (env->ExceptionCheck()){
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                }
                break;
            }
        }
    }
}

static int set_nonblock(int fd){
    int flags;
    if ((flags = fcntl(fd, F_GETFL, NULL)) == -1)
        return -1;
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1)
        return -1;
    return 0;
}

static int looper_callback(int fd, int events, void *data){
    if (events & ALOOPER_EVENT_INPUT){
        JNIEnv* env;
        if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK){
            jobject network_ref = (jobject)data;
            read_addresses(env, network_ref, fd);
        }
    }
    return 1;
}

static void jni_networks_poll(JNIEnv *env, jobject object) {
    int fd = netlink_socket();

    request_addresses(fd);
    set_nonblock(fd);

    ALooper *looper = ALooper_forThread();
    if (!looper){
        LOGIF("Failed to get looper");
        return;
    }

    jobject network_ref = env->NewGlobalRef(object);

    if (ALooper_addFd(looper, fd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, looper_callback,
                  network_ref)!=1)
        LOGIF("Failed to add file descriptor %d", fd);
}

#define NELS(X) (sizeof(X) / sizeof(X[0]))

static JNINativeMethod networks_methods[] = {
        {"beginPolling", "()V", (void*)jni_networks_poll },
};

int jni_register_networks(JNIEnv* env){
    jclass networks = env->FindClass("org/servalproject/succinct/networking/Networks");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    jni_onAdd = env->GetMethodID(networks, "onAdd", "(Ljava/lang/String;[B[BI)V");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    jni_onRemove = env->GetMethodID(networks, "onRemove", "(Ljava/lang/String;[B[BI)V");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    env->RegisterNatives(networks, networks_methods, NELS(networks_methods));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    return 0;
}
