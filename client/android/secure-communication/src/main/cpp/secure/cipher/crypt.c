//
//  Crypt.c
//  sc
//
//  Created by yaoqi.li on 2022/7/7.
//
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "crypt.h"
#include "sm4.h"

#define DEFCSTRING(name, string)\
    int len##name = (int)strlen(string);\
    char pp##name[len##name + 1];\
    char* p##name = pp##name;\
    p##name[len##name] = 0;\
    strncpy(p##name, string, len##name);


unsigned char *sm4_encrypt(int model, char *string, int string_len, char *key , char *iv, int *output_len) {
    
//    DEFCSTRING(Msg, string); 兼容byte时使用传递的string_len
    int lenMsg = string_len;
    char ppMsg[lenMsg + 1];
    char* pMsg = ppMsg;
    pMsg[lenMsg] = 0;
    strncpy(pMsg, string, lenMsg);
    
    DEFCSTRING(Key, key);
    DEFCSTRING(Iv, iv);
    
    int padNum = 16 - lenMsg % 16; //padding 1~16 char
    unsigned char* pad = calloc(sizeof(unsigned char),padNum + lenMsg);
    memcpy(pad, pMsg, lenMsg);
    for (int i = 0; i < padNum; ++i) {
        pad[i + lenMsg] = (unsigned char)padNum;
    }
    
    bce_sm4_context ctx;
    bce_sm4_setkey_enc(&ctx, (unsigned char*)pKey);
    if(model == CBC){
        bce_sm4_crypt_cbc(&ctx, 1, padNum + lenMsg, (unsigned char *)pIv, pad, pad);
    }else{
        bce_sm4_crypt_ecb(&ctx, 1, padNum + lenMsg, pad, pad);
    }
    
    *output_len=padNum + lenMsg;
    return pad;
}

unsigned char *sm4_decrypt(int model, char *string, int string_len, char *key, char *iv, int *output_len) {
    
//    DEFCSTRING(Msg, string);兼容byte时使用传递的string_len
    int lenMsg = string_len;
    char ppMsg[lenMsg + 1];
    char* pMsg = ppMsg;
    pMsg[lenMsg] = 0;
    strncpy(pMsg, string, lenMsg);
    
    DEFCSTRING(Key, key);
    DEFCSTRING(Iv, iv);
    
    unsigned char* output = calloc(sizeof(unsigned char) , lenMsg + 1);
    memset(output, 0, lenMsg + 1);
    
    bce_sm4_context ctx;
    bce_sm4_setkey_dec(&ctx, (unsigned char*)pKey);
    if(model == CBC){
        bce_sm4_crypt_cbc(&ctx, 0, lenMsg, (unsigned char *)pIv,
                      (unsigned char *)string, output);
    }else{
        bce_sm4_crypt_ecb(&ctx, 0, lenMsg,(unsigned char *)string, output);
    }
    
    int outLen = (int)strlen((char*)output);
    int padNum = output[outLen - 1];
    if (outLen - padNum <= 0)
        return NULL;
    for (int i = 1; i <= padNum; ++i) {
        if (output[outLen - i] != padNum)
            return NULL;
    }
    
    return output;
}


unsigned char *sm4_CBC_encrypt(char *string, int string_len, char *key, char *iv, int *output_len) {
    return sm4_encrypt(CBC, string,string_len, key, iv, output_len);
}

unsigned char *sm4_CBC_decrypt(char *string, int string_len,  char *key, char *iv, int *output_len) {
    return sm4_decrypt(CBC, string,string_len, key, iv, output_len);
}

unsigned char *sm4_ECB_encrypt(char *string, int string_len, char *key, int *output_len) {
    return sm4_encrypt(ECB, string, string_len, key, "useless iv",output_len);
}

unsigned char *sm4_ECB_decrypt(char *string, int string_len, char *key, int *output_len) {
    return sm4_decrypt(ECB, string, string_len, key, "useless iv",output_len);
}
