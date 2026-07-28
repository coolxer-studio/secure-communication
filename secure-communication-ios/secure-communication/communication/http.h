//
// Created by yaoqi.li on 2022/5/29.
//

#ifndef HTTP_H
#define HTTP_H
#define HTTP_DEFAULT_PORT 80

char* http_get(const char* url, const char* header);
char* http_post(const char* url, const char* header, const char* body);

#endif
