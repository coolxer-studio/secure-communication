//
//  sc.m
//  sc
//
//  Created by yaoqi.li on 2022/8/10.
//

#import "CTSecureCommunication.h"
#import "secure/security.h"
#import "communication/http.h"

// MARK:  ReserveHttp
@interface ReserveHttp : NSObject
@end

@interface ReserveHttp()<NSURLSessionDelegate>
@end

@implementation ReserveHttp
{
    NSURLSession* _session;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        NSURLSessionConfiguration* config = [NSURLSessionConfiguration defaultSessionConfiguration];
        // 客户端3秒请求超时
        config.timeoutIntervalForRequest = 3;
        _session = [NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:nil];
    }
    return self;
}

//https server credential handing, default behavior is trust to all.
- (void)URLSession:(NSURLSession *)session didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
 completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition disposition,
                             NSURLCredential * _Nullable credential))completionHandler {
    NSURLSessionAuthChallengeDisposition disposition =
                NSURLSessionAuthChallengePerformDefaultHandling;
    __block NSURLCredential *credential = nil;
    
    if ([challenge.protectionSpace.authenticationMethod
         isEqualToString:NSURLAuthenticationMethodServerTrust]) {
        credential = [NSURLCredential credentialForTrust:challenge.protectionSpace.serverTrust];
        if (credential) { //trust all
            disposition=NSURLSessionAuthChallengeUseCredential;
        }
    }
    
    if (completionHandler) {
        completionHandler(disposition,credential);
    }
}

//BCERemoteSession is not support asynchronous send
- (NSString *)sessionTaskForURLRequest:(NSURLRequest*)request{
    __block NSString *responseData = nil;
    // 创建信号量，实现阻塞任务
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    NSURLSessionDataTask* task = [_session dataTaskWithRequest:request
           completionHandler:^(NSData * _Nullable data, NSURLResponse * _Nullable response,NSError * _Nullable error) {
        // 200ok后提取响应结果
        NSHTTPURLResponse *httpResp = (NSHTTPURLResponse*)response;
        if (httpResp.statusCode == 200)     {
            responseData = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        }else {
            responseData = [[NSString alloc] initWithFormat:@"{\"status\":%ld}",(long)httpResp.statusCode];
        }
        // 发送信号量
        dispatch_semaphore_signal(semaphore);
    }];
    [task resume];
    // 等待到信号量后执行，保证阻塞
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);
    return responseData;
}

+ (instancetype)sharedInstance {
    static ReserveHttp* instance;
    // dispatch_once函数实现单例模式
    static dispatch_once_t predicate;
    // predicate用来保证执行一次，第二个参数是要执行一次的任务block
    dispatch_once(&predicate, ^{
        instance = [[ReserveHttp alloc] init];
    });
    return instance;
}
@end

// MARK: CommonUtils
@interface CommonUtils : NSObject

@end

@implementation CommonUtils

static NSString* identifyStr;

+ (NSString *)bundleSeedID {
    NSDictionary *query = [NSDictionary dictionaryWithObjectsAndKeys:
                           (__bridge id)kSecClassGenericPassword, (__bridge id)kSecClass,
                           @"bundleSeedID", (__bridge id)kSecAttrAccount,
                           @"", (__bridge id)kSecAttrService,
                           (id)kCFBooleanTrue, (__bridge id)kSecReturnAttributes,
                           nil];
    CFDictionaryRef result = nil;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, (CFTypeRef *)&result);
    if (status == errSecItemNotFound)
        status = SecItemAdd((__bridge CFDictionaryRef)query, (CFTypeRef *)&result);
    if (status != errSecSuccess)
        return nil;
    NSString *accessGroup = [(__bridge NSDictionary *)result objectForKey:(__bridge id)kSecAttrAccessGroup];
    NSArray *components = [accessGroup componentsSeparatedByString:@"."];
    NSString *bundleSeedID = [[components objectEnumerator] nextObject];
    CFRelease(result);
    return bundleSeedID;
}

+ (NSString *)getIdentify{
    if(!identifyStr){
        identifyStr = [NSString stringWithFormat:@"%@-%@", [CommonUtils bundleSeedID], [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleIdentifier"]];
    }
    return identifyStr;
}

+ (void) doneFirstRun{
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
    [defaults setBool:TRUE forKey:@"KEY_RUN_Multiple"];
}

+ (Boolean) isFirstRun{
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
    return ![defaults boolForKey:@"KEY_RUN_Multiple"];
}

+ (void) setReserveTimes:(NSInteger) value{
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
    [defaults setInteger:value forKey:@"KEY_RESERVE_TIMES"];
}

+ (NSInteger) reserveTimes{
    NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
    return [defaults integerForKey:@"KEY_RESERVE_TIMES"];
}

@end

// MARK: main

//static const NSString* host = @"http://192.168.1.201:11099";
static const NSString* host = @"http://39.106.54.18:11099";
static const NSString* prefixUri = @"/sc/";
static const NSString* scid = @"scid:debug\r\n";
static const BOOL identifyOpen = TRUE;


@implementation CTSecureCommunication

// MARK: main-public

+ (NSString *)get:(NSString *_Nonnull)uri withHeader:(NSString *)header{
    return[CTSecureCommunication http:uri withHeader:header withBody:nil];
}

+ (NSString *)post:(NSString *_Nonnull)uri withHeader:(NSString *)header withBody:(NSString *_Nonnull)body{
    return[CTSecureCommunication http:uri withHeader:header withBody:body];
}


// MARK: main-private
+ (NSString *)http:(NSString *_Nonnull)uri withHeader:(NSString *)header withBody:(NSString *_Nonnull)body{
    NSString *response;
    if([CommonUtils isFirstRun]){
        // 第一次运行需要调用标准库获取网络权限
        response = [CTSecureCommunication reserveHttp:uri withHeader:header withBody:body];
        [CommonUtils doneFirstRun];
    }else if(YES || [CommonUtils reserveTimes]>0){
        response = [CTSecureCommunication reserveHttp:uri withHeader:header withBody:body];
        [CommonUtils setReserveTimes:[CommonUtils reserveTimes]-1];
    }else{
        [CommonUtils setReserveTimes:10];
        response = [CTSecureCommunication defaultHttp:uri withHeader:header withBody:body];
        [CommonUtils setReserveTimes:0];
    }
    
    return response;
    
}

+ (NSString *)defaultHttp:(NSString *_Nonnull)uri withHeader:(NSString *)header withBody:(NSString *_Nonnull)body{
    NSString* tmpUri = uri;
    if(identifyOpen){
        tmpUri = [[CommonUtils getIdentify] stringByAppendingFormat:@"->%@",uri];
    }
    NSString* url = [host stringByAppendingFormat:@"%@%s",prefixUri,encrypt_uri([tmpUri UTF8String])];
    if(header){
        header = [scid stringByAppendingString:header];
    }else{
        header = scid;
    }
    char* encrytpResponse;
    if(body){
        char* aa = [url UTF8String];
        char* bb =[header UTF8String];
        char* cc = encrypt_body([body UTF8String]);
        encrytpResponse = http_post(aa, bb, cc);
    }else{
        encrytpResponse = http_get([url UTF8String], [header UTF8String]);
    }
    char* decryptResponse = decrypt_body(encrytpResponse);
    free(encrytpResponse);
    NSString *decryptResponseNSString = [NSString stringWithUTF8String:decryptResponse];
    free(decryptResponse);
    return decryptResponseNSString;
}

+ (NSString *)reserveHttp:(NSString *_Nonnull)uri withHeader:(NSString *)header withBody:(NSString *_Nonnull)body{
    NSString* url = [host stringByAppendingFormat:@"%@%@%@",prefixUri,@"reserve/",[CTSecureCommunication base64EncryptUri:uri]];
    NSURL *url_tmp = [NSURL URLWithString:url];
    NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:url_tmp];
    
    if(header){
        header = [scid stringByAppendingString:header];
    }else{
        header = scid;
    }
    NSArray<NSString *> *headerArray = [header componentsSeparatedByString:@"\r\n"];
    for (NSString *item in headerArray) {
        NSArray<NSString *> *kv = [item componentsSeparatedByString:@":"];
        if(kv.count > 1){
            [request addValue:kv[1] forHTTPHeaderField:kv[0]];
        }
    }
    if(body){
        request.HTTPMethod = @"POST";
    }else{
        request.HTTPMethod = @"GET";
    }
    [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    request.HTTPBody = [[CTSecureCommunication base64EncryptBody:body] dataUsingEncoding:NSUTF8StringEncoding];
    NSString *responseData = [[ReserveHttp sharedInstance] sessionTaskForURLRequest:request];
    if(responseData){
        return [CTSecureCommunication base64DecryptBody:responseData];
    }else{
        return nil;
    }
}

+ (NSString *)base64EncryptUri:(NSString *) uri{
    NSString *newUri = [NSString stringWithFormat:@"%d%@",arc4random()%10000,uri];
    NSString *base64Uri = [[newUri dataUsingEncoding: NSUTF8StringEncoding] base64EncodedStringWithOptions:0];
    NSString *replaceStr = [[[base64Uri stringByReplacingOccurrencesOfString:@"+" withString:@"!"] stringByReplacingOccurrencesOfString:@"/" withString:@"@"]stringByReplacingOccurrencesOfString:@"=" withString:@"*"];
    return [CTSecureCommunication reverseCase:replaceStr];
}

+ (NSString *)base64EncryptBody:(NSString *) body{
    NSString *base64Body = [[body dataUsingEncoding: NSUTF8StringEncoding] base64EncodedStringWithOptions:0];
    return [CTSecureCommunication reverseCase:base64Body];
}

+ (NSString *)base64DecryptBody:(NSString *) body{
    NSString *base64Str = [CTSecureCommunication reverseCase:body];
    NSData *data = [[NSData alloc]initWithBase64EncodedString:base64Str options:0];
    if(data){
        return [[NSString alloc]initWithData:data encoding: NSUTF8StringEncoding];
    }
    return body;
}

+ (NSString *) reverseCase:(NSString *)str
{
    int length = [str length];
    NSMutableString *result = [NSMutableString stringWithCapacity:length];

    for (int i = 0; i < length; i++)
    {
        unichar ch = [str characterAtIndex:i];
        if (islower(ch))
            ch = toupper(ch);
        else if (isupper(ch))
            ch = tolower(ch);
        [result appendString:[NSString stringWithCharacters:&ch length:1]];
    }

    return result;
}

@end

