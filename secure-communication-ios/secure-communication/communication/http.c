//
// Created by yaoqi.li on 2022/5/29.
//

#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h> // for open
#include <unistd.h> // for close
#include <errno.h>
#include "http.h"

#define BUFFER_SIZE 10240
#define HTTP_POST "POST /%s HTTP/1.1\r\n"\
    "HOST: %s:%d\r\nAccept: */*\r\nContent-Length: %d\r\n%s"\
    "\r\n%s"
#define HTTP_GET "GET /%s HTTP/1.1\r\n"\
    "HOST: %s:%d\r\nAccept: */*\r\n%s"\
    "\r\n"

static int http_content_length(char response[]) {
    char* contentLengthStart = strstr(response, "Content-Length: ");
    if (contentLengthStart == NULL) {
        printf("Content-Length not found\n");
        return 0;
    }

    // 找到 Content-Length 字段后的数值
    char* contentLengthValueStart = contentLengthStart + strlen("Content-Length: ");
    char* contentLengthValueEnd = strchr(contentLengthValueStart, '\r');
    if (contentLengthValueEnd == NULL) {
        printf("Invalid response format\n");
        return 0;
    }

    // 提取数值并打印
    int contentLength = atoi(contentLengthValueStart);
    printf("Content-Length: %d\n", contentLength);

    return contentLength;
}

static int http_header_length(char response[]) {
    char* lastCRLF = strrchr(response, '\r');
    if (lastCRLF == NULL || lastCRLF[1] == '\0') {
        printf("Invalid response format\n");
        return 0;
    }

    // 获取最后一个 \r\n 前面的字符串长度
    int headerLength = lastCRLF-response+2;
    
    printf("httpHeaderLength: %d\n", headerLength);

    return headerLength;
}

static int http_tcpclient_create(const char *host, int port) {
    struct hostent *he;
    struct sockaddr_in server_addr;
    int socket_fd;

    if ((he = gethostbyname(host)) == NULL) {
        return -1;
    }

    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);
    server_addr.sin_addr = *((struct in_addr *) he->h_addr);

    if ((socket_fd = socket(AF_INET, SOCK_STREAM, 0)) == -1) {
        printf("http_tcpclient_create failed error:%s(error:%d)\\n", strerror(errno), errno);
        return -1;
    }

    if (connect(socket_fd, (struct sockaddr *) &server_addr, sizeof(struct sockaddr)) == -1) {
        return -1;
    }

    return socket_fd;
}

/*
 * 关闭连接
 * */
static void http_tcpclient_close(int socket) {
    close(socket);
}

/*
 * 解析URL
 * */
static int http_parse_url(const char *url, char *host, char *file, int *port) {
    char *ptr1, *ptr2;
    int len = 0;
    if (!url || !host || !file || !port) {
        return -1;
    }

    ptr1 = (char *) url;

    if (!strncmp(ptr1, "http://", strlen("http://"))) {
        ptr1 += strlen("http://");
    } else {
        return -1;
    }

    ptr2 = strchr(ptr1, '/');
    if (ptr2) {
        len = strlen(ptr1) - strlen(ptr2);
        memcpy(host, ptr1, len);
        host[len] = '\0';
        if (*(ptr2 + 1)) {
            memcpy(file, ptr2 + 1, strlen(ptr2) - 1);
            file[strlen(ptr2) - 1] = '\0';
        }
    } else {
        memcpy(host, ptr1, strlen(ptr1));
        host[strlen(ptr1)] = '\0';
    }
    //get host and ip
    ptr1 = strchr(host, ':');
    if (ptr1) {
        *ptr1++ = '\0';
        *port = atoi(ptr1);
    } else {
        *port = HTTP_DEFAULT_PORT;
    }

    return 0;
}


static int http_tcpclient_recv(int socket, char *lpbuff) {

    int recvnum = 0;
    int contextLength = 0;
    int headerLength = 0;
    int totalBytes = 0;
    int remainingBytes = BUFFER_SIZE;
    while (totalBytes < BUFFER_SIZE * 4) {
        recvnum = recv(socket, lpbuff + totalBytes, remainingBytes, 0);
        if(contextLength == 0){
            contextLength = http_content_length(lpbuff);
            headerLength = http_header_length(lpbuff);
        }
        if (recvnum > 0) {
            totalBytes += recvnum;
            remainingBytes -= recvnum;
            if(totalBytes-headerLength >= contextLength){
                break;
            }
        } else if (recvnum == 0) {
            printf("Connection closed by the remote side\n");
            break;
        } else {
            perror("recv failed");
            // 处理错误
            // ...
            break;
        }
    }
    return totalBytes;
}

static int http_tcpclient_send(int socket, char *buff, int size) {
    int sent = 0, tmpres = 0;

    while (sent < size) {
        tmpres = send(socket, buff + sent, size - sent, 0);
        if (tmpres == -1) {
            return -1;
        }
        sent += tmpres;
    }
    return sent;
}

static char *http_parse_result(const char *lpbuf) {
    char *ptmp = NULL;
    char *response = NULL;
    ptmp = (char *) strstr(lpbuf, "HTTP/1.1");
    if (!ptmp) {
        printf("http/1.1 not faind\n");
        return NULL;
    }
    if (atoi(ptmp + 9) != 200) {
        printf("result:\n%s\n", lpbuf);
        return NULL;
    }

    ptmp = (char *) strstr(lpbuf, "\r\n\r\n");
    if (!ptmp) {
        printf("ptmp is NULL\n");
        return NULL;
    }
    int response_size = strlen(ptmp) + 1;
    response = (char *) malloc(response_size);
    memset(response,0x00,response_size);
    if (!response) {
        printf("malloc failed \n");
        return NULL;
    }
    strcpy(response, ptmp + 4);
    return response;
}

/*
 * Post请求
 * */
char *http_post(const char *url, const char *header, const char *body) {

    int socket_fd = -1;
    char lpbuf_request[BUFFER_SIZE * 200] = {'\0'};
    char lpbuf_response[BUFFER_SIZE * 4] = {'\0'};
    char *ptmp;
    char host_addr[BUFFER_SIZE] = {'\0'};
    char file[BUFFER_SIZE] = {'\0'};
    int port = 0;
    int len = 0;
    char *response = NULL;

    if (!url || !body) {
        printf("      failed!\n");
        return NULL;
    }

    if (http_parse_url(url, host_addr, file, &port)) {
        printf("http_parse_url failed!\n");
        return NULL;
    }
    //printf("host_addr : %s\tfile:%s\t,%d\n",host_addr,file,port);

    socket_fd = http_tcpclient_create(host_addr, port);
    if (socket_fd < 0) {
        printf("http_tcpclient_create failed error:%s(error:%d)\\n", strerror(errno), errno);
        return NULL;
    }

    sprintf(lpbuf_request, HTTP_POST, file, host_addr, port, strlen(body), header, body);

    if (http_tcpclient_send(socket_fd, lpbuf_request, strlen(lpbuf_request)) < 0) {
        printf("http_tcpclient_send failed..\n");
        return NULL;
    }
    //printf("发送请求:\n%s\n",lpbuf_request);

    /*it's time to recv from server*/
    if (http_tcpclient_recv(socket_fd, lpbuf_response) <= 0) {
        printf("http_tcpclient_recv failed\n");
        return NULL;
    }

    http_tcpclient_close(socket_fd);

    return http_parse_result(lpbuf_response);
}

/*
 * Get请求
 * */
char *http_get(const char *url, const char *header) {

    int socket_fd = -1;
    char lpbuf_request[BUFFER_SIZE * 40] = {'\0'};
    char lpbuf_response[BUFFER_SIZE * 4] = {'\0'};
    char *ptmp;
    char host_addr[BUFFER_SIZE] = {'\0'};
    char file[BUFFER_SIZE] = {'\0'};
    int port = 0;
    int len = 0;

    if (!url) {
        printf("      failed!\n");
        return NULL;
    }

    if (http_parse_url(url, host_addr, file, &port)) {
        printf("http_parse_url failed!\n");
        return NULL;
    }
    //printf("host_addr : %s\tfile:%s\t,%d\n",host_addr,file,port);

    socket_fd = http_tcpclient_create(host_addr, port);
    if (socket_fd < 0) {
        printf("http_tcpclient_create failed error:%s(error:%d)\\n", strerror(errno), errno);
        return NULL;
    }

    sprintf(lpbuf_request, HTTP_GET, file, host_addr, port, header);

    if (http_tcpclient_send(socket_fd, lpbuf_request, strlen(lpbuf_request)) < 0) {
        printf("http_tcpclient_send failed..\n");
        return NULL;
    }
    //	printf("发送请求:\n%s\n",lpbuf_request);

    if (http_tcpclient_recv(socket_fd, lpbuf_response) <= 0) {
        printf("http_tcpclient_recv failed\n");
        return NULL;
    }
    http_tcpclient_close(socket_fd);

    return http_parse_result(lpbuf_response);
}



