
#include <string>
#include <android/log.h>
#include <malloc.h>
#include "lmdb/lmdb.h"
#include "hash.h"
#include "storage.h"

#define LOGIF(X, ...) __android_log_print(ANDROID_LOG_INFO, "StorageJNI", X, ##__VA_ARGS__)

// current snapshot
#define MAX_NAME (256)

struct file_version{
    uint32_t version; // version
    char name[MAX_NAME]; // relative path
};

struct file_data{
    // committed data (for syncing etc)
    uint32_t version; // version
    uint64_t length;
    uint8_t hash[crypto_hash_sha256_BYTES];
    crypto_hash_sha256_state partial_hash;
    char name[MAX_NAME]; // relative path
    // in progress append
    uint64_t new_length;
    crypto_hash_sha256_state new_hash;
};
#define PERSIST_LEN (offsetof(struct file_data, name))

struct dbstate{
    MDB_env *env;
    MDB_dbi files;
    MDB_dbi index;
    uint8_t root_hash[crypto_hash_sha256_BYTES];
};

static jmethodID jni_file_callback;

static void close_db(struct dbstate *state){
    LOGIF("close_db");
    if (state->files)
        mdb_dbi_close(state->env, state->files);
    if (state->index)
        mdb_dbi_close(state->env, state->index);
    mdb_env_close(state->env);
    memset(&state, 0, sizeof state);
}

static int open_db(struct dbstate *state, const char *path){
    MDB_txn *txn;

    LOGIF("open_db %s", path);

    if (mdb_env_create(&state->env))
        return -1;
    if (mdb_env_open(state->env, path, MDB_NOSUBDIR , 0))
        goto error;
    if (mdb_txn_begin(state->env, NULL, 0, &txn))
        goto error;

    if (mdb_dbi_open(txn, "files", MDB_CREATE, &state->files)){
        mdb_txn_abort(txn);
        goto error;
    }

    if (mdb_dbi_open(txn, "index", MDB_CREATE, &state->index)){
        mdb_txn_abort(txn);
        goto error;
    }

    // reload the root hash
    MDB_val key;
    MDB_val val;

    key.mv_data = (void *)"_";
    key.mv_size = 1;

    if (mdb_get(txn, state->index, &key, &val) == 0 && val.mv_size == sizeof state->root_hash){
        memcpy(state->root_hash, val.mv_data, sizeof state->root_hash);
    }

    if (mdb_txn_commit(txn) != 0)
        goto error;

    return 0;

error:
    close_db(state);
    return -1;
}

static struct file_data *file_open(struct dbstate *state, const char *name){
    MDB_txn *txn;
    LOGIF("file_open %s", name);
    if (mdb_txn_begin(state->env, NULL, MDB_RDONLY, &txn)!=0)
        return NULL;

    MDB_val key;
    MDB_val val;

    key.mv_data=(void *)name;
    key.mv_size=strlen(name);
    if (key.mv_size>MAX_NAME-1)
        key.mv_size=MAX_NAME-1;

    int r = mdb_get(txn, state->files, &key, &val);
    if (r!=0 && r!=MDB_NOTFOUND) {
        mdb_txn_abort(txn);
        return NULL;
    }

    struct file_data *ret = (file_data *) malloc(sizeof(struct file_data));
    if (!ret){
        mdb_txn_abort(txn);
        return NULL;
    }

    memset(ret, 0, sizeof *ret);

    // sanity check...
    if (r != MDB_NOTFOUND && val.mv_size != PERSIST_LEN){
        LOGIF("WRONG LENGTH! (%d vs %d)", (int)val.mv_size, PERSIST_LEN);
        r = MDB_NOTFOUND;
    }

    if (r == MDB_NOTFOUND){
        crypto_hash_sha256_init(&ret->partial_hash);
    } else {
        memcpy(ret, val.mv_data, PERSIST_LEN);
    }

    mdb_txn_commit(txn);
    memcpy(ret->name, key.mv_data, key.mv_size);
    ret->new_length = ret->length;
    ret->new_hash = ret->partial_hash;
    return ret;
}

static void file_append(struct file_data *file, uint8_t *data, size_t len){
    crypto_hash_sha256_update(&file->new_hash, data, len);
    file->new_length+=len;
}

static int file_flush(struct dbstate *state, struct file_data *file){
    MDB_txn *txn;
    MDB_val key;
    MDB_val val;
    struct file_version new_version;
    uint8_t old_hash[crypto_hash_sha256_BYTES];

    file->version++;
    file->length = file->new_length;
    file->partial_hash = file->new_hash;

    memcpy(old_hash, file->hash, sizeof file->hash);

    crypto_hash_sha256_state hash_state = file->new_hash;
    crypto_hash_sha256_final(&hash_state, file->hash);

    memset(&new_version, 0, sizeof new_version);

    strcpy(new_version.name, file->name);
    new_version.version = file->version;

    if (mdb_txn_begin(state->env, NULL, 0, &txn)!=0)
        return -1;

    key.mv_data = (void *)file->name;
    key.mv_size = strlen(file->name);
    val.mv_data = file;
    val.mv_size = PERSIST_LEN;

    if (mdb_put(txn, state->files, &key, &val, 0)!=0)
        goto error;

    // TODO purge really old index records?
    
    key.mv_data = (void *)file->hash;
    key.mv_size = sizeof file->hash;
    val.mv_data = &new_version;
    val.mv_size = sizeof new_version;

    if (mdb_put(txn, state->index, &key, &val, 0)!=0)
        goto error;

    // XOR the old and new hash with the root hash
    for(unsigned i=0;i<sizeof old_hash;i++)
        old_hash[i] ^= state->root_hash[i] ^ file->hash[i];

    key.mv_data = (void *)"_";
    key.mv_size = 1;
    val.mv_data = old_hash;
    val.mv_size = sizeof old_hash;

    if (mdb_put(txn, state->index, &key, &val, 0)!=0)
        goto error;

    if (mdb_txn_commit(txn) !=0)
        goto error;

    memcpy(state->root_hash, old_hash, sizeof old_hash);
    LOGIF("flushed file %s, len %d", file->name, (int)file->length);
    return 0;

error:
    mdb_txn_abort(txn);
    return -1;
}

static jlong JNICALL jni_storage_open(JNIEnv *env, jobject object, jstring path)
{
    struct dbstate *state = (struct dbstate*)malloc(sizeof(struct dbstate));
    memset(state, 0, sizeof *state);

    const char *filename = env->GetStringUTFChars(path, NULL);
    int r = open_db(state, filename);
    if (r){
        free(state);
        state = NULL;
    }
    env->ReleaseStringUTFChars(path, filename);
    return (jlong)state;
}

static void JNICALL jni_storage_close(JNIEnv *env, jobject object, jlong ptr)
{
    struct dbstate *state = (struct dbstate *)ptr;
    close_db(state);
}

static jlong JNICALL jni_file_open(JNIEnv *env, jobject object, jlong store_ptr, jstring name)
{
    struct dbstate *state = (struct dbstate *)store_ptr;
    const char *filename = env->GetStringUTFChars(name, NULL);
    struct file_data *ret = file_open(state, filename);
    env->ReleaseStringUTFChars(name, filename);
    env->CallVoidMethod(object, jni_file_callback, (jlong)ret->length);
    return (jlong)ret;
}

static void JNICALL jni_file_close(JNIEnv *env, jobject object, jlong file_ptr)
{
    struct file_data *file = (file_data *) file_ptr;
    free(file);
}

static void JNICALL jni_file_append(JNIEnv *env, jobject object, jlong file_ptr, jbyteArray bytes, jint offset, jint len)
{
    struct file_data *file = (file_data *) file_ptr;
    jbyte buff[len];
    env->GetByteArrayRegion(bytes, offset, len, buff);
    file_append(file, (uint8_t *) buff, (size_t)len);
}

static jint JNICALL jni_file_flush(JNIEnv *env, jobject object, jlong store_ptr, jlong file_ptr)
{
    struct dbstate *state = (struct dbstate *)store_ptr;
    struct file_data *file = (file_data *) file_ptr;
    int ret = file_flush(state, file);
    env->CallVoidMethod(object, jni_file_callback, (jlong)file->length);
    return (jint)ret;
}

#define NELS(X) (sizeof(X) / sizeof(X[0]))

static JNINativeMethod storage_methods[] = {
        {"open", "(Ljava/lang/String;)J", (void*)jni_storage_open },
        {"close", "(J)V", (void*)jni_storage_close },
};

static JNINativeMethod file_methods[] = {
        {"open", "(JLjava/lang/String;)J", (void*)jni_file_open },
        {"append", "(J[BII)V", (void*)jni_file_append },
        {"flush", "(JJ)I", (void*)jni_file_flush },
        {"close", "(J)V", (void*)jni_file_close },
};

int jni_register_storage(JNIEnv* env){
    jclass store = env->FindClass("org/servalproject/succinct/storage/Storage");
    if (env->ExceptionCheck())
        return -1;

    env->RegisterNatives(store, storage_methods, NELS(storage_methods));
    if (env->ExceptionCheck())
        return -1;

    jclass file = env->FindClass("org/servalproject/succinct/storage/RecordStore");
    if (env->ExceptionCheck())
        return -1;
    jni_file_callback = env->GetMethodID(file, "jniCallback", "(J)V");
    if (env->ExceptionCheck())
        return -1;
    env->RegisterNatives(file, file_methods, NELS(file_methods));
    if (env->ExceptionCheck())
        return -1;
    return 0;
}

