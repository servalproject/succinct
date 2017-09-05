#include <jni.h>
#include "storage.h"
#include "networks.h"
#include "native-lib.h"
#include "forms/compression.h"

JavaVM* java_vm;

extern "C"{
    JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
    {
        java_vm = vm;

        JNIEnv* env;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            return -1;
        }

        if (jni_register_storage(env) == -1)
            return -1;

        if (jni_register_networks(env) == -1)
            return -1;

        if (jni_register_compression(env) == -1)
            return -1;

        return JNI_VERSION_1_6;
    }
}
