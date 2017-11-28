
#include <string>
#include <android/log.h>
#include <malloc.h>
#include "lmdb/lmdb.h"
#include "hash.h"
#include "storage.h"
#include "sync_keys.h"
#include "native-lib.h"

#define LOGIF(X, ...) __android_log_print(ANDROID_LOG_INFO, "StorageJNI", X, ##__VA_ARGS__)

// current snapshot
#define MAX_NAME (256)

#define SYNC_MSG_KEY 0
#define SYNC_MSG_REQ_METADATA 1
#define SYNC_MSG_SEND_METADATA 2

#define VERSION 2

struct file_version{
    uint32_t version;
    uint64_t length;
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

struct root_state{
    uint8_t hash[crypto_hash_sha256_BYTES];
    uint8_t version;
};

struct dbstate{
    jobject storage;
    MDB_env *env;
    MDB_dbi files;
    MDB_dbi index;
    struct root_state root;
    struct sync_state *sync_state;
};

static jmethodID jni_file_callback;
static jmethodID jni_store_callback;
static jmethodID jni_sync_message;
static jmethodID jni_peer_has;

static void close_db(struct dbstate *state){
    LOGIF("close_db");
    if (state->sync_state)
        sync_free_state(state->sync_state);
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

    if (mdb_env_create(&state->env)){
        LOGIF("mdb_env_create failed");
        return -1;
    }
    mdb_env_set_maxdbs(state->env, 2);
    if (mdb_env_open(state->env, path, 0, 0)) {
        LOGIF("mdb_env_create failed");
        goto error;
    }
    if (mdb_txn_begin(state->env, NULL, 0, &txn)){
        LOGIF("mdb_txn_begin failed");
        goto error;
    }

    if (mdb_dbi_open(txn, "files", MDB_CREATE, &state->files)){
        LOGIF("mdb_dbi_open files failed");
        mdb_txn_abort(txn);
        goto error;
    }

    if (mdb_dbi_open(txn, "index", MDB_CREATE, &state->index)){
        LOGIF("mdb_dbi_open index failed");
        mdb_txn_abort(txn);
        goto error;
    }

    // reload the root hash
    MDB_val key;
    MDB_val val;

    key.mv_data = (void *)"_";
    key.mv_size = 1;

    if (mdb_get(txn, state->index, &key, &val) == 0){
        if (val.mv_size == sizeof state->root)
            memcpy(&state->root, val.mv_data, sizeof state->root);

        if (state->root.version != VERSION){
            LOGIF("%d != %d, CLEARING DATABASE", state->root.version, VERSION);
            mdb_drop(txn, state->index, 0);
            mdb_drop(txn, state->files, 0);
            memset(&state->root, 0, sizeof state->root);
        }
    }
    state->root.version = VERSION;

    if (mdb_txn_commit(txn) != 0){
        LOGIF("mdb_txn_commit failed");
        goto error;
    }

    return 0;

error:
    close_db(state);
    return -1;
}

static int send_message(JNIEnv* env, jobject object, uint8_t type, const uint8_t *data, size_t len){
    if (!env){
        if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
            return -1;
    }
    jbyteArray argument = env->NewByteArray(len+1);
    env->SetByteArrayRegion(argument, 0, 1, (const jbyte *) &type);
    env->SetByteArrayRegion(argument, 1, len, (const jbyte *) data);
    env->CallVoidMethod(object, jni_sync_message, argument);
    env->DeleteLocalRef(argument);
    return 0;
}

static void call_peer_has(JNIEnv* env, jobject peer, const sync_key_t *sync_key, const struct file_version *version){
    jbyteArray key = env->NewByteArray(sizeof(sync_key_t));
    env->SetByteArrayRegion(key, 0, sizeof(sync_key_t), (const jbyte *) sync_key->key);
    jstring filename = env->NewStringUTF(version->name);
    env->CallVoidMethod(peer, jni_peer_has, key, filename, (jlong)version->length);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(filename);
}

static int process_difference(struct dbstate *state, jobject peer, const sync_key_t *sync_key){
    // do we already know what this hash refers to?
    JNIEnv* env;
    if (java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return -1;

    MDB_txn *txn;
    if (mdb_txn_begin(state->env, NULL, MDB_RDONLY, &txn)!=0)
        return -1;

    MDB_val key;
    MDB_val val;

    key.mv_data = (void *)sync_key;
    key.mv_size = sizeof(sync_key_t);

    int r = mdb_get(txn, state->files, &key, &val);
    if (r == MDB_NOTFOUND){
        send_message(env, peer, SYNC_MSG_REQ_METADATA, (const uint8_t *) sync_key, sizeof(sync_key_t));
    }else if(r==0){
        const struct file_version *peer_version = (const file_version *) val.mv_data;

        // we could compare their version to ours here,
        // but it's likely that we'll need to call file_open anyway, or have already done so.
        call_peer_has(env, peer, sync_key, peer_version);
    }
    mdb_txn_abort(txn);
    return 0;
}

static void send_metadata(JNIEnv* env, struct dbstate *state, jobject peer, const sync_key_t *sync_key){
    MDB_txn *txn;
    if (mdb_txn_begin(state->env, NULL, MDB_RDONLY, &txn)!=0)
        return;

    MDB_val key;
    MDB_val val;

    key.mv_data = (void *)sync_key;
    key.mv_size = sizeof(sync_key_t);

    int r = mdb_get(txn, state->index, &key, &val);
    if (r == MDB_NOTFOUND){
        LOGIF("Metadata not found??? (%x %x %x ...)", sync_key->key[0], sync_key->key[1], sync_key->key[2]);
    } else if (r==0){
        const struct file_version *peer_version = (const file_version *) val.mv_data;
        jbyteArray argument = env->NewByteArray(sizeof(sync_key_t)+sizeof(struct file_version)+1);
        uint8_t type = SYNC_MSG_SEND_METADATA;
        env->SetByteArrayRegion(argument, 0, 1, (const jbyte *) &type);
        env->SetByteArrayRegion(argument, 1, sizeof(sync_key_t), (const jbyte *) sync_key);
        env->SetByteArrayRegion(argument, 1+sizeof(sync_key_t), sizeof(struct file_version), (const jbyte *) peer_version);
        env->CallVoidMethod(peer, jni_sync_message, argument);
        env->DeleteLocalRef(argument);
    }else{
        LOGIF("Other error? %d", r);
    }
    mdb_txn_abort(txn);
}

static void receive_metadata(JNIEnv* env, struct dbstate *state, jobject peer, const uint8_t *msg, size_t len){
    if (len != sizeof(sync_key_t) + sizeof(struct file_version))
        return;

    const sync_key_t *sync_key = (const sync_key_t *) msg;
    const struct file_version *peer_version = (const file_version *) (msg + sizeof(sync_key_t));

    MDB_txn *txn;
    if (mdb_txn_begin(state->env, NULL, 0, &txn)!=0)
        return;

    MDB_val key;
    MDB_val val;

    key.mv_data = (void *)sync_key;
    key.mv_size = sizeof(sync_key_t);
    val.mv_data = (void *) peer_version;
    val.mv_size = sizeof(struct file_version);

    if (mdb_put(txn, state->index, &key, &val, 0)!=0)
        goto error;
    if (mdb_txn_commit(txn)!=0)
        goto error;

    call_peer_has(env, peer, sync_key, peer_version);
    return;

error:
    mdb_txn_abort(txn);
}

static void has_callback(void *context, void *peer_context, const sync_key_t *sync_key) {
    process_difference((dbstate *) context, (jobject) peer_context, sync_key);
}

static void does_not_have_callback(void *context, void *peer_context, void *key_context, const sync_key_t *key){
    struct dbstate *state = (dbstate *) context;
}

static void now_has_callback(void *context, void *peer_context, void *key_context, const sync_key_t *sync_key){
    process_difference((dbstate *) context, (jobject) peer_context, sync_key);
}

static void queue_message(void *context, void *peer_context, const uint8_t *buff, size_t len){
    send_message(NULL, (jobject)peer_context, SYNC_MSG_KEY, buff, len);
}

static void init_sync_state(struct dbstate *state){
    if (state->sync_state)
        return;

    MDB_txn *txn;
    if (mdb_txn_begin(state->env, NULL, MDB_RDONLY, &txn)!=0)
        return;

    state->sync_state = sync_alloc_state(state, has_callback, does_not_have_callback, now_has_callback, queue_message);

    // add the current hash of every file
    MDB_cursor *curs;
    mdb_cursor_open(txn, state->files, &curs);
    MDB_val key;
    MDB_val val;
    if (mdb_cursor_get(curs, &key, &val, MDB_FIRST)==0){
        do {
            if (val.mv_size == PERSIST_LEN) {
                struct file_data *data = (file_data *) val.mv_data;
                sync_add_key(state->sync_state, (const sync_key_t*)data->hash, NULL);
            }
        }while(mdb_cursor_get(curs, &key, &val, MDB_NEXT)==0);
    }
    mdb_cursor_close(curs);
    mdb_txn_abort(txn);
}

static struct file_data *file_open(struct dbstate *state, const char *name){
    MDB_txn *txn;
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
        LOGIF("WRONG LENGTH! (%d vs %d)", (int)val.mv_size, (int)PERSIST_LEN);
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

static int file_flush(struct dbstate *state, struct file_data *file, const uint8_t *expected_hash, size_t hash_len) {
    MDB_txn *txn;
    MDB_val key;
    MDB_val val;
    struct file_version new_version;
    struct file_data new_data;
    struct root_state new_root;

    if (file->length == file->new_length)
        return 0;

    new_data = *file;
    crypto_hash_sha256_final(&new_data.new_hash, new_data.hash);

    if (hash_len && memcmp(expected_hash, new_data.hash, hash_len) != 0) {
        LOGIF("Hash comparison failed");
        goto rewind;
    }

    new_data.version++;
    new_data.partial_hash = new_data.new_hash = file->new_hash;
    new_data.length = new_data.new_length;

    if (mdb_txn_begin(state->env, NULL, 0, &txn) != 0) {
        LOGIF("mdb_txn_begin failed");
        goto rewind;
    }

    key.mv_data = (void *) file->name;
    key.mv_size = strlen(file->name);
    val.mv_data = &new_data;
    val.mv_size = PERSIST_LEN;

    if (mdb_put(txn, state->files, &key, &val, 0) != 0) {
        LOGIF("mdb_put failed");
        goto error;
    }

    // TODO purge really old index records?
    memset(&new_version, 0, sizeof new_version);
    strcpy(new_version.name, file->name);
    new_version.version = new_data.version;
    new_version.length = new_data.length;

    key.mv_data = (void *) new_data.hash;
    key.mv_size = sizeof(sync_key_t);
    val.mv_data = &new_version;
    val.mv_size = sizeof new_version;

    if (mdb_put(txn, state->index, &key, &val, 0) != 0) {
        LOGIF("mdb_put failed");
        goto error;
    }

    // XOR the old and new hash with the root hash
    for (unsigned i = 0; i < sizeof new_root; i++)
        new_root.hash[i] = state->root.hash[i] ^ new_data.hash[i] ^ file->hash[i];
    new_root.version = VERSION;

    key.mv_data = (void *) "_";
    key.mv_size = 1;
    val.mv_data = &new_root;
    val.mv_size = sizeof new_root;

    if (mdb_put(txn, state->index, &key, &val, 0) != 0) {
        LOGIF("mdb_put failed");
        goto error;
    }

    if (mdb_txn_commit(txn) != 0) {
        LOGIF("mdb_txn_commit failed");
        goto error;
    }

    // Now we can and must update the state of the file
    memcpy(&state->root, &new_root, sizeof new_root);
    *file = new_data;

    if (state->sync_state) {
        // TODO remove old sync keys?
        sync_add_key(state->sync_state, (sync_key_t *) file->hash, NULL);
    }

    LOGIF("flushed file %s, len %d", file->name, (int)file->length);
    return 1;

error:
    mdb_txn_abort(txn);

rewind:
    file->new_length = file->length;
    file->new_hash = file->partial_hash;
    return -1;
}

static void storage_callback(JNIEnv *env, struct dbstate *state){
    jbyteArray root = env->NewByteArray(sizeof state->root.hash);
    env->SetByteArrayRegion(root, 0, sizeof state->root.hash, (const jbyte *) state->root.hash);
    env->CallVoidMethod(state->storage, jni_store_callback, root);
    env->DeleteLocalRef(root);
}

static jlong JNICALL jni_storage_open(JNIEnv *env, jobject object, jstring path)
{
    struct dbstate *state = (struct dbstate*)malloc(sizeof(struct dbstate));
    memset(state, 0, sizeof *state);

    state->storage = env->NewGlobalRef(object);
    const char *filename = env->GetStringUTFChars(path, NULL);
    int r = open_db(state, filename);
    if (r){
        free(state);
        state = NULL;
    }
    env->ReleaseStringUTFChars(path, filename);
    storage_callback(env, state);
    return (jlong)state;
}

static void JNICALL jni_storage_close(JNIEnv *env, jobject object, jlong ptr)
{
    struct dbstate *state = (struct dbstate *)ptr;
    env->DeleteGlobalRef(state->storage);
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

static jint JNICALL jni_file_flush(JNIEnv *env, jobject object, jlong store_ptr, jlong file_ptr, jbyteArray expectedHash)
{
    struct dbstate *state = (struct dbstate *)store_ptr;
    struct file_data *file = (file_data *) file_ptr;

    int hash_length = expectedHash ? env->GetArrayLength(expectedHash) : 0;
    uint8_t hash[hash_length];
    if (hash_length)
        env->GetByteArrayRegion(expectedHash, 0, hash_length, (jbyte *) hash);
    int ret = file_flush(state, file, hash, (size_t)hash_length);
    if (ret!=0){
        jbyteArray new_hash = env->NewByteArray(sizeof file->hash);
        env->SetByteArrayRegion(new_hash, 0, sizeof file->hash, (const jbyte *) file->hash);
        env->CallVoidMethod(object, jni_file_callback, (jlong)file->length, new_hash);
    }
    if (ret==1)
        storage_callback(env, state);
    return (jint)ret;
}

static jlong JNICALL jni_peer_message(JNIEnv *env, jobject object, jlong store_ptr, jlong sync_state, jbyteArray message){
    struct dbstate *state = (struct dbstate *)store_ptr;
    struct sync_peer_state *peer_state = (sync_peer_state *) sync_state;

    if (message){
        if (!state->sync_state)
            init_sync_state(state);
        if (!peer_state)
            peer_state = sync_alloc_peer_state(state->sync_state, env->NewGlobalRef(object));

        jsize len = env->GetArrayLength(message);
        uint8_t msg[len];
        env->GetByteArrayRegion(message, 0, len, (jbyte *) msg);

        switch(msg[0]){
            case SYNC_MSG_KEY:
                sync_recv_message(state->sync_state, peer_state, msg+1, (size_t) len -1);
                break;
            case SYNC_MSG_REQ_METADATA:
                send_metadata(env, state, object, (const sync_key_t *) (msg + 1));
                break;
            case SYNC_MSG_SEND_METADATA:
                receive_metadata(env, state, object, msg+1, (size_t)len -1);
                break;
            default:
                // unknown message type...
                LOGIF("Unhandled type %d?", msg[0]);
                break;
        }

    }else if(peer_state){
        void *context = sync_free_peer_state(state->sync_state, peer_state);
        env->DeleteGlobalRef((jobject) context);
        peer_state = NULL;
    }
    return (jlong)peer_state;
}

static void jni_queue_root_message(JNIEnv *env, jobject peer, jlong store_ptr){
    struct dbstate *state = (struct dbstate *)store_ptr;
    if (!state->sync_state)
        init_sync_state(state);

    uint8_t msg_buff[64];
    size_t len = sync_root_msg(state->sync_state, msg_buff, sizeof msg_buff);
    send_message(env, peer, SYNC_MSG_KEY, msg_buff, len);
}

#define NELS(X) (sizeof(X) / sizeof(X[0]))

static JNINativeMethod storage_methods[] = {
        {"open", "(Ljava/lang/String;)J", (void*)jni_storage_open },
        {"close", "(J)V", (void*)jni_storage_close },
};

static JNINativeMethod file_methods[] = {
        {"open", "(JLjava/lang/String;)J", (void*)jni_file_open },
        {"append", "(J[BII)V", (void*)jni_file_append },
        {"flush", "(JJ[B)I", (void*)jni_file_flush },
        {"close", "(J)V", (void*)jni_file_close },
};

static JNINativeMethod peer_methods[] = {
        {"processSyncMessage", "(JJ[B)J", (void*)jni_peer_message },
        {"queueRootMessage", "(J)V", (void*)jni_queue_root_message }
};

int jni_register_storage(JNIEnv* env){
    jclass store = env->FindClass("org/servalproject/succinct/storage/Storage");
    if (env->ExceptionCheck())
        return -1;

    jni_store_callback = env->GetMethodID(store, "jniCallback", "([B)V");
    if (env->ExceptionCheck())
        return -1;
    env->RegisterNatives(store, storage_methods, NELS(storage_methods));
    if (env->ExceptionCheck())
        return -1;

    jclass file = env->FindClass("org/servalproject/succinct/storage/RecordStore");
    if (env->ExceptionCheck())
        return -1;
    jni_file_callback = env->GetMethodID(file, "jniCallback", "(J[B)V");
    if (env->ExceptionCheck())
        return -1;
    env->RegisterNatives(file, file_methods, NELS(file_methods));
    if (env->ExceptionCheck())
        return -1;

    jclass peer = env->FindClass("org/servalproject/succinct/networking/Peer");
    if (env->ExceptionCheck())
        return -1;
    jni_sync_message = env->GetMethodID(peer, "syncMessage", "([B)V");
    if (env->ExceptionCheck())
        return -1;
    jni_peer_has = env->GetMethodID(peer, "peerHas", "([BLjava/lang/String;J)V");
    if (env->ExceptionCheck())
        return -1;
    env->RegisterNatives(peer, peer_methods, NELS(peer_methods));
    if (env->ExceptionCheck())
        return -1;

    return 0;
}

