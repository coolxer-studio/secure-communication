//
//  security_h
//  sc
//
//  Created by yaoqi.li on 2022/7/7.
//

#include <stdlib.h>
#include <string.h>
#include "security.h"
#include "rand/rand_key.h"
#include "cipher/crypt.h"
#include "basex/baseencode.h"

#define STDKEY "7dFg7SFKEVIND4fD"
#define STDIV "WIAw89fW6bFh9WsS"

char* encrypt_uri(char *data){
    char *key = get_rand_key(16);
    
    int len;
    unsigned char *encrypt_data = sm4_ECB_encrypt(data,strlen(data),key,&len);
    
    baseencode_error_t err;
    char *ek = base32_encode(encrypt_data, len, &err, 0);
    
    char *return_uri = mix_key_str(key,ek);
    free(key);
    return return_uri;
}

char* encrypt_body(char *data){
    int len;
    unsigned char *encrypt_data = sm4_CBC_encrypt(data,strlen(data),STDKEY,STDIV,&len);
    baseencode_error_t err;
    char *ek = base64_encode(encrypt_data, len, &err);
    return ek;
}

char* decrypt_body(char *data){
    baseencode_error_t err;
    unsigned char *decrypt_data = base64_decode(data, strlen(data), &err);
    // 因为是byte数据，本身存在/0，为了获取数据的真实长度，base64最大是原来数据的3/4，去除最后面的\0后的长度就是真实长度
    size_t decrypt_data_length = strlen(data) / 4 * 3;
    for(int i=decrypt_data_length-1;i>0;i--){
        if(decrypt_data[i] =='\0'){
            decrypt_data_length-=1;
        }else{
            break;
        }
    }
    int len;
    return sm4_CBC_decrypt(decrypt_data,decrypt_data_length,STDKEY,STDIV,&len);
}


//unsigned char sm4_CBC_entrypt(char *string,char *key ,char *iv);
//unsigned char sm4_CBC_decrypt(char *string,char *key, char *iv);
