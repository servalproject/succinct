#ifndef SUCCINCT_HASH_H
#define SUCCINCT_HASH_H

#include <stdint.h>

typedef struct crypto_hash_sha256_state {
    uint32_t state[8];
    uint64_t count;
    uint8_t  buf[64];
} crypto_hash_sha256_state;

#define crypto_hash_sha256_BYTES 32U

#ifdef __cplusplus
extern "C"{
#endif

int crypto_hash_sha256(unsigned char *out, const unsigned char *in,
                       unsigned long long inlen);

int crypto_hash_sha256_init(crypto_hash_sha256_state *state);

int crypto_hash_sha256_update(crypto_hash_sha256_state *state,
                              const unsigned char *in,
                              unsigned long long inlen);

int crypto_hash_sha256_final(crypto_hash_sha256_state *state,
                             unsigned char *out);

#ifdef __cplusplus
};
#endif

#endif //SUCCINCT_HASH_H
