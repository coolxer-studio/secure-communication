//
//  crypt.h
//  sc
//
//  Created by yaoqi.li on 2022/7/7.
//

#ifndef crypt_h
#define crypt_h
#define CBC     0
#define ECB     1

#include <stdio.h>
unsigned char *sm4_CBC_encrypt(char *string, int string_len, char *key ,char *iv, int *output_len);
unsigned char *sm4_CBC_decrypt(char *string, int string_len, char *key, char *iv, int *output_len);

unsigned char *sm4_ECB_encrypt(char *string, int string_len, char *key, int *output_len);
unsigned char *sm4_ECB_decrypt(char *string, int string_len, char *key, int *output_len);
#endif /* crypt_h */
