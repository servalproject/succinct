#include <string.h>
#include "compression.h"
#include "recipe.h"
#include "log.h"
#include "smac.h"
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#define LOGIF(X, ...) __android_log_print(ANDROID_LOG_INFO, "CompressionJNI", X, ##__VA_ARGS__)

jmethodID recipe_callback;

static jlong open_stats(JNIEnv *env, jobject object, jobject asset_manager, jstring filename){
    AAssetManager *am = AAssetManager_fromJava(env, asset_manager);
    const char *path = env->GetStringUTFChars(filename, NULL);

    AAsset *a = AAssetManager_open(am, path, 0);
    off_t len = AAsset_getLength(a);
    const uint8_t *buff = (const uint8_t *) AAsset_getBuffer(a);

    stats_handle *h = stats_map_file(buff, (size_t)len);
    h->reserved = a;

    stats_load_tree(h);

    return (jlong)h;
}

static void close_stats(JNIEnv *env, jobject object, jlong stats_ptr){
    stats_handle *h = (stats_handle *) stats_ptr;
    AAsset *a = (AAsset *) h->reserved;
    stats_handle_free(h);
    AAsset_close(a);
}

static void build_recipe(JNIEnv *env, jobject object, jstring form_spec){
    char form_name[1024] = "";
    char recipe_text[0x10000] = "";
    size_t recipe_len = sizeof recipe_text;

    const char *specification = env->GetStringUTFChars(form_spec, NULL);

    int r = xhtmlToRecipe(specification,
                          NULL, 0,
                          form_name, sizeof form_name,
                          recipe_text, &recipe_len,
                          NULL, NULL);

    env->ReleaseStringUTFChars(form_spec, specification);

    if (r)
        // TODO throw?
        return;

    struct recipe *recipe = recipe_read(form_name, recipe_text, recipe_len);

    if (!recipe)
        // TODO throw?
        return;

    jbyteArray hash = env->NewByteArray(sizeof recipe->formhash);
    env->SetByteArrayRegion(hash, 0, sizeof recipe->formhash, (const jbyte *) recipe->formhash);

    env->CallVoidMethod(object, recipe_callback,
                        env->NewStringUTF(form_name),
                        hash,
                        env->NewStringUTF(recipe_text),
                        (jlong)recipe);
}

static void close_recipe(JNIEnv *env, jobject object, jlong recipe_ptr){
    recipe_free((struct recipe *) recipe_ptr);
}

static jstring strip_form(JNIEnv *env, jobject object, jstring form_instance){
    const char *instance = env->GetStringUTFChars(form_instance, NULL);

    char stripped[0x10000] = "";
    int stripped_len = xml2stripped(NULL, instance, strlen(instance), stripped, sizeof(stripped));

    env->ReleaseStringUTFChars(form_instance, instance);
    if (stripped_len<=0)
        // TODO throw?
        return NULL;

    return env->NewStringUTF(stripped);
}

static jbyteArray compress_form(JNIEnv *env, jobject object, jlong stats_ptr, jlong recipe_ptr, jstring stripped_form){
    struct recipe *recipe = (struct recipe *) recipe_ptr;
    stats_handle *h = (stats_handle *)stats_ptr;

    const char *stripped = env->GetStringUTFChars(stripped_form, NULL);

    uint8_t succinct[1024];

    int succinct_len = recipe_compress(h, recipe, stripped, strlen(stripped), succinct, sizeof(succinct));

    env->ReleaseStringUTFChars(stripped_form, stripped);

    jbyteArray compressed = env->NewByteArray(succinct_len);
    env->SetByteArrayRegion(compressed, 0, succinct_len, (const jbyte *) succinct);

    return compressed;
}

static jbyteArray compress_string(JNIEnv *env, jobject object, jlong stats_ptr, jstring str) {
    stats_handle *h = (stats_handle *) stats_ptr;

    const char *string = env->GetStringUTFChars(str, NULL);

    unsigned in_len = strlen(string);
    uint8_t buff[in_len];
    unsigned len = sizeof buff;

    int r = stats3_compress(string, in_len, buff, &len, h);
    if (r != 0) {
        env->ReleaseStringUTFChars(str, string);
        return NULL;
    }

    jbyteArray compressed = env->NewByteArray(len);
    env->SetByteArrayRegion(compressed, 0, len, (const jbyte *) buff);

    return compressed;
}

#define NELS(X) (sizeof(X) / sizeof(X[0]))

static JNINativeMethod stats_methods[] = {
        {"openStats", "(Landroid/content/res/AssetManager;Ljava/lang/String;)J", (void*)open_stats },
        {"closeStats", "(J)V", (void*)close_stats },
        {"compressString", "(JLjava/lang/String;)[B", (void*)compress_string }
};

static JNINativeMethod recipe_methods[] = {
        {"buildRecipe", "(Ljava/lang/String;)V", (void*)build_recipe },
        {"closeRecipe", "(J)V", (void*)close_recipe },
        {"stripForm", "(Ljava/lang/String;)Ljava/lang/String;", (void*)strip_form },
        {"compressForm", "(JJLjava/lang/String;)[B", (void*)compress_form }
};

int jni_register_compression(JNIEnv* env){

    jclass stats = env->FindClass("org/servalproject/succinct/forms/Stats");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    env->RegisterNatives(stats, stats_methods, NELS(stats_methods));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    jclass recipe = env->FindClass("org/servalproject/succinct/forms/Recipe");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    recipe_callback = env->GetMethodID(recipe, "callback", "(Ljava/lang/String;[BLjava/lang/String;J)V");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    env->RegisterNatives(recipe, recipe_methods, NELS(recipe_methods));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        return -1;
    }
    return 0;
}
