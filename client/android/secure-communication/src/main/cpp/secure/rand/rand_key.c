//
// Created by yaoqi.li on 2022/6/5.
//

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <time.h>
#include "rand_key.h"

void get_rand_str(char str[], int num, char *rand_dic) {
    char rand_str[2] = {0};
    unsigned long dic_len = strlen(rand_dic);
    srand((unsigned int) time((time_t *) NULL));//使用系统时间来初始化随机数发生器
    for (int i = 1; i <= num; i++) {//按指定大小返回相应的字符串
        sprintf(rand_str, "%c", rand_dic[(rand() % dic_len)]);
        strcat(str, rand_str);
    }
}

// 生成key,需要释放返回值
char *get_rand_key(int size) {
    char *rand_dic = "abcdefghijklmnopqrstuvwxyz";
    char *str = (char *)malloc(size+1);
    memset(str, 0x00, size+1);
    get_rand_str(str, size, rand_dic);
    return str;
}

//混合key和str，需要释放返回值
char *mix_key_str(char key[],char str[]) {
    unsigned long key_size=strlen(key);
    int key_index = 0;
    unsigned long str_size=strlen(str);
    int str_index = 0;
    unsigned long key_str_size = key_size+str_size+1;
    char *key_str = malloc(key_str_size);
    srand((unsigned int) time((time_t *) NULL));
    for (int i = 0; i<key_str_size;i++){
        if((rand()%3)==0){
            if(key_index<key_size){
                key_str[i]=key[key_index++];
                continue;
            }
        }
        if(str_index<str_size){
            key_str[i]=str[str_index++];
        }else if(key_index<key_size){
            key_str[i]=key[key_index++];
        } else{
            key_str[i]='\0';
        }
    }
    return key_str;
}

/*
int main() {
    char* key = get_rand_key(16);
    char* str = "HELLOSC";
    printf("\nkey:%s", key);
    printf("\nstr:%s", str);
    printf("\nkey_str:%s",mix_key_str(key,str));
}
*/
