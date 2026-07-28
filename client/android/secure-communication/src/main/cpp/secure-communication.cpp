// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("secure-communication");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("secure-communication")
//      }
//    }



#include <jni.h>
#include <stdio.h>
#include <string>
#include <android/log.h>

extern "C" {
#include "communication/http.h"
#include "secure/security.h"
}

//#define HOST_PORT "http://39.106.54.18:11099"
#define HOST_PORT "http://192.168.1.12:11099"
#define PREFIX_URI "/sc/"
#define identifyFlg 1
#define SCID "scid:debug\r\n"

#define RESERVE_KEY "KEY_RESERVE_TIMES"

//函数声明
jobject getApplication(JNIEnv *pEnv);
char *getPackageName(JNIEnv *pEnv);
char *getSignature(JNIEnv *pEnv);
jstring charTojstring(JNIEnv* env, const char* pat);
char *Jstring2CStr(JNIEnv *pEnv, jstring pJstring);

void save_shared_preferences(JNIEnv* env, jstring key, jint value);
jint get_shared_preferences(JNIEnv* env, jstring key, jint defaultValue);

//定义输出的TAG
const char *LOG_TGA = "LOG_TGA_SC";
const char HexCode[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

jstring http(JNIEnv *env,jobject thiz, jstring uri, jstring header, jstring body);
jstring http(
        JNIEnv *env,
        jobject thiz, jstring uri, jstring header, jstring body) {
    // 01：uri处理
    char* original_uri = Jstring2CStr(env, uri);
    if(identifyFlg){
        char* packageName = getPackageName(env);
        char* signature = getSignature(env);
        char *tmp_uri = static_cast<char *>(malloc(strlen(original_uri)+strlen(packageName)+strlen(signature)+4));
        sprintf(tmp_uri,"%s-%s->%s",signature,packageName,original_uri);
        free(original_uri);
        original_uri = tmp_uri;
    }

    // 混合密钥和密文
    char *encriptUri = encrypt_uri(original_uri);
    // 拼装target_url
    int target_url_size = strlen(HOST_PORT) + strlen(PREFIX_URI) + strlen(encriptUri) + 1;
    char *target_url = static_cast<char *>(malloc(target_url_size));
    memset(target_url, 0x00, target_url_size);
    strcat(target_url, HOST_PORT);
    strcat(target_url, PREFIX_URI);
    strcat(target_url, encriptUri);
    // 释放资源
    free(original_uri);
    original_uri = nullptr;

    // 02：header处理
    char *append_header;
    if(header == nullptr){
        append_header = static_cast<char *>(malloc(strlen(SCID)+1));
        sprintf(append_header,"%s",SCID);
    }else{
        char *original_header = Jstring2CStr(env, header);
        append_header = static_cast<char *>(malloc(strlen(original_header)+strlen(SCID)+1));
        sprintf(append_header,"%s%s",SCID,original_header);
        free(original_header);
    }

    char *response_body = nullptr;
    if (body != nullptr) {
        // 03：post请求处理body
        char *original_body = Jstring2CStr(env, body);
        char *target_body = encrypt_body(original_body);
        response_body = http_post(target_url, append_header, target_body);
        free(original_body);
        original_body = nullptr;
        free(target_body);
        target_body = nullptr;
    } else {
        response_body = http_get(target_url, append_header);
    }
    free(append_header);
    append_header = nullptr;
    append_header = nullptr;
    append_header = nullptr;
    free(target_url);
    target_url = nullptr;
    if(!response_body){
        __android_log_print(ANDROID_LOG_ERROR, LOG_TGA, "target_url:%s",target_url);
        response_body = "";
    }
    char *plainBody = decrypt_body(response_body);
    jstring stringUtf = (*env).NewStringUTF(plainBody);
    free(plainBody);
    return stringUtf;
}
jstring reserve_get(JNIEnv *env, jstring uri, jstring header);
jstring reserve_get(JNIEnv *env, jstring uri, jstring header){
    jclass cls_HttpUtil = env->FindClass("com/coolxer/securecommunication/utils/HttpUtil");
    if (cls_HttpUtil != 0) {
        jmethodID jm_decode = env->GetStaticMethodID(cls_HttpUtil, "decode", "(Ljava/lang/String;)Ljava/lang/String;");
        jmethodID jm_encode_uri = env->GetStaticMethodID(cls_HttpUtil, "encodeUri", "(Ljava/lang/String;)Ljava/lang/String;");
        jmethodID jm_http_post = env->GetStaticMethodID(cls_HttpUtil, "sendGetRequest", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        // 拼接字符串
        jobject encode_uri = env->CallStaticObjectMethod(cls_HttpUtil, jm_encode_uri, uri);
        char *encode_uri_char =  Jstring2CStr(env, (jstring) encode_uri);
        std::string url = std::string(HOST_PORT) + std::string(PREFIX_URI) + std::string("reserve/")+std::string(encode_uri_char);
        char *url_char = const_cast<char *>(url.c_str());
        jstring url_jstring = env->NewStringUTF(url_char);
        jobject responseString = env->CallStaticObjectMethod(cls_HttpUtil, jm_http_post, url_jstring, header);
        if(responseString != nullptr){
            jobject encode_body = env->CallStaticObjectMethod(cls_HttpUtil, jm_decode, (jstring)responseString);
            // 释放局部引用
            env->DeleteLocalRef(cls_HttpUtil);
            return (jstring) encode_body;
        }
        // 释放局部引用
        env->DeleteLocalRef(cls_HttpUtil);
    }
    return nullptr;
}

jstring reserve_post(JNIEnv *env, jstring uri, jstring header, jstring body);
jstring reserve_post(JNIEnv *env, jstring uri, jstring header, jstring body){
    jclass cls_HttpUtil = env->FindClass("com/coolxer/securecommunication/utils/HttpUtil");
    if (cls_HttpUtil != 0) {
        jmethodID jm_decode = env->GetStaticMethodID(cls_HttpUtil, "decode", "(Ljava/lang/String;)Ljava/lang/String;");
        jmethodID jm_encode = env->GetStaticMethodID(cls_HttpUtil, "encode", "(Ljava/lang/String;)Ljava/lang/String;");
        jmethodID jm_encode_uri = env->GetStaticMethodID(cls_HttpUtil, "encodeUri", "(Ljava/lang/String;)Ljava/lang/String;");
        jmethodID jm_http_post = env->GetStaticMethodID(cls_HttpUtil, "sendPostRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        // 拼接字符串
        jobject encode_uri = env->CallStaticObjectMethod(cls_HttpUtil, jm_encode_uri, uri);
        char *encode_uri_char =  Jstring2CStr(env, (jstring) encode_uri);
        std::string url = std::string(HOST_PORT) + std::string(PREFIX_URI) + std::string("reserve/")+std::string(encode_uri_char);
        jobject encode_body = env->CallStaticObjectMethod(cls_HttpUtil, jm_encode, body);
        char *url_char = const_cast<char *>(url.c_str());
        jstring url_jstring = env->NewStringUTF(url_char);
        jobject responseString = env->CallStaticObjectMethod(cls_HttpUtil, jm_http_post, url_jstring, header, encode_body);
        if(responseString != nullptr){
            jobject encode_body = env->CallStaticObjectMethod(cls_HttpUtil, jm_decode, (jstring)responseString);
            // 释放局部引用
            env->DeleteLocalRef(cls_HttpUtil);
            return (jstring) encode_body;
        }
        // 释放局部引用
        env->DeleteLocalRef(cls_HttpUtil);
    }
    return env->NewStringUTF("null");
}

jstring get(
        JNIEnv *env,
        jobject thiz, jstring uri, jstring header) {
    jstring response_body;
    jint times = get_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),0);
    if(times >0){
        save_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),times-1);
        response_body = reserve_get(env,uri, header);
    }else{
        save_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),10);
        response_body = http(env,thiz,uri, header, nullptr);
        save_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),0);
    }
    return response_body;
}
jstring post(
        JNIEnv *env,
        jobject thiz, jstring uri, jstring header, jstring body) {
    jstring response_body;
    jint times = get_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),0);
    if(times >0){
        save_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),times-1);
        response_body = reserve_post(env,uri, header, body);
    }else{
        save_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),10);
        response_body = http(env,thiz,uri, header, body);
        save_shared_preferences(env,env->NewStringUTF(RESERVE_KEY),0);
    }
    return response_body;

}


// 动态注册JNI
jint JNI_OnLoad(JavaVM *javaVM, void *reserved) {
    JNIEnv *jniEnv;
    if (JNI_OK == javaVM->GetEnv((void **) (&jniEnv), JNI_VERSION_1_4)) {
        // 动态注册的Java函数所在的类
        jclass registerClass = jniEnv->FindClass("com/coolxer/securecommunication/CTSecureCommunication");
        JNINativeMethod jniNativeMethods[] = {
                // 映射关系
                {"post", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", (void *) (post)},
                {"get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", (void *) (get)}
        };
        if (jniEnv->RegisterNatives(registerClass, jniNativeMethods,
                                    sizeof(jniNativeMethods) / sizeof((jniNativeMethods)[0])) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TGA, "RegisterNatives ERROR!");
            jniEnv->DeleteLocalRef(registerClass);
            return JNI_ERR;
        }
        jniEnv->DeleteLocalRef(registerClass);
    }
    return JNI_VERSION_1_4;
}

jstring charTojstring(JNIEnv* env, const char* pat) {
    //定义java String类 strClass
    jclass strClass = env->FindClass("java/lang/String");
    //获取String(byte[],String)的构造器,用于将本地byte[]数组转换为一个新String
    jmethodID ctorID = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    //建立byte数组
    jbyteArray bytes = env->NewByteArray(strlen(pat));
    //将char* 转换为byte数组
    env->SetByteArrayRegion(bytes, 0, strlen(pat), (jbyte*) pat);
    // 设置String, 保存语言类型,用于byte数组转换至String时的参数
    jstring encoding = env->NewStringUTF("GB2312");
    //将byte数组转换为java String,并输出
    jstring result = (jstring) env->NewObject(strClass, ctorID, bytes, encoding);
    env->DeleteLocalRef(strClass);
    return result;
}

// 内部函数实现
char *Jstring2CStr(JNIEnv *env, jstring jstr) {
    char* rtn = NULL;
    jclass clsstring = env->FindClass("java/lang/String");
    jstring strencode = env->NewStringUTF("GB2312");
    jmethodID mid = env->GetMethodID(clsstring, "getBytes", "(Ljava/lang/String;)[B");
    jbyteArray barr = (jbyteArray) env->CallObjectMethod(jstr, mid, strencode);
    jsize alen = env->GetArrayLength(barr);
    jbyte* ba = env->GetByteArrayElements(barr, JNI_FALSE);
    if (alen > 0) {
        rtn = (char*) malloc(alen + 1);
        memcpy(rtn, ba, alen);
        rtn[alen] = 0;
    }
    env->DeleteLocalRef(clsstring);
    env->ReleaseByteArrayElements(barr, ba, 0);
    return rtn;
}

char* getSignature(JNIEnv *env) {
    //获取到Context
    jobject context = getApplication(env);
    if (context == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TGA, "context is null!");
        return NULL;
    }
    jclass  activity = env->GetObjectClass(context);
    // 得到 getPackageManager 方法的 ID
    jmethodID methodID_func = env->GetMethodID(activity, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    // 获得PackageManager对象
    jobject packageManager = env->CallObjectMethod(context, methodID_func);
    jclass packageManagerclass = env->GetObjectClass(packageManager);
    //得到 getPackageName 方法的 ID
    jmethodID methodID_pack = env->GetMethodID(activity, "getPackageName", "()Ljava/lang/String;");
    //获取包名
    jstring name_str = (jstring)(env->CallObjectMethod(context, methodID_pack));
    // 得到 getPackageInfo 方法的 ID
    jmethodID methodID_pm = env->GetMethodID(packageManagerclass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    // 获得应用包的信息
    jobject package_info = env->CallObjectMethod(packageManager, methodID_pm, name_str, 64);
    // 获得 PackageInfo 类
    jclass package_infoclass = env->GetObjectClass(package_info);
    // 获得签名数组属性的 ID
    jfieldID fieldID_signatures = env->GetFieldID(package_infoclass, "signatures", "[Landroid/content/pm/Signature;");
    // 得到签名数组，待修改
    jobject signatur = env->GetObjectField(package_info, fieldID_signatures);
    jobjectArray  signatures = (jobjectArray)(signatur);
    // 得到签名
    jobject signature = env->GetObjectArrayElement(signatures, 0);
    // 获得 Signature 类，待修改
    jclass signature_clazz = env->GetObjectClass(signature);
    //---获得签名byte数组
    jmethodID tobyte_methodId = env->GetMethodID(signature_clazz, "toByteArray", "()[B");
    jbyteArray signature_byte = (jbyteArray) env->CallObjectMethod(signature, tobyte_methodId);
    //把byte数组转成流
    jclass byte_array_input_class = env->FindClass("java/io/ByteArrayInputStream");
    jmethodID init_methodId = env->GetMethodID(byte_array_input_class, "<init>", "([B)V");
    jobject byte_array_input = env->NewObject(byte_array_input_class, init_methodId, signature_byte);
    //实例化X.509
    jclass certificate_factory_class = env->FindClass("java/security/cert/CertificateFactory");
    jmethodID certificate_methodId = env->GetStaticMethodID(certificate_factory_class, "getInstance", "(Ljava/lang/String;)Ljava/security/cert/CertificateFactory;");
    jstring x_509_jstring = env->NewStringUTF("X.509");
    jobject cert_factory = env->CallStaticObjectMethod(certificate_factory_class, certificate_methodId, x_509_jstring);
    //certFactory.generateCertificate(byteIn);
    jmethodID certificate_factory_methodId = env->GetMethodID(certificate_factory_class, "generateCertificate", ("(Ljava/io/InputStream;)Ljava/security/cert/Certificate;"));
    jobject x509_cert = env->CallObjectMethod(cert_factory, certificate_factory_methodId, byte_array_input);

    jclass x509_cert_class = env->GetObjectClass(x509_cert);
    jmethodID x509_cert_methodId = env->GetMethodID(x509_cert_class, "getEncoded", "()[B");
    jbyteArray cert_byte = (jbyteArray)env->CallObjectMethod(x509_cert, x509_cert_methodId);

    //MessageDigest.getInstance("SHA1")
    jclass message_digest_class = env->FindClass("java/security/MessageDigest");
    jmethodID methodId = env->GetStaticMethodID(message_digest_class, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    //如果取SHA1则输入SHA1
    //jstring sha1_jstring=env->NewStringUTF(env,"SHA1");
    jstring sha1_jstring = env->NewStringUTF("MD5");
    jobject sha1_digest = env->CallStaticObjectMethod(message_digest_class, methodId, sha1_jstring);
    //sha1.digest (certByte)
    methodId = env->GetMethodID(message_digest_class, "digest", "([B)[B");
    jbyteArray sha1_byte = (jbyteArray)env->CallObjectMethod(sha1_digest, methodId, cert_byte);
    //toHexString
    jsize array_size = env->GetArrayLength(sha1_byte);
    jbyte *sha1 = env->GetByteArrayElements(sha1_byte, NULL);
    char hex_sha[array_size * 2 + 1];
    int i;
    for (i = 0; i < array_size; ++i)
    {
        hex_sha[2 * i] = HexCode[((unsigned char)sha1[i]) / 16];
        hex_sha[2 * i + 1] = HexCode[((unsigned char)sha1[i]) % 16];
    }
    hex_sha[array_size * 2] = '\0';
    char* p = static_cast<char *>(malloc(array_size * 2 + 1));
    strcpy(p,hex_sha);
    // 释放
    env->DeleteLocalRef(byte_array_input_class);
    env->DeleteLocalRef(certificate_factory_class);
    env->DeleteLocalRef(message_digest_class);
    return p;
}

char* getPackageName(JNIEnv *env) {
    jobject context = getApplication(env);
    if (context == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TGA, "context is null!");
        return NULL;
    }
    jclass activity = env->GetObjectClass(context);
    jmethodID methodId_pack = env->GetMethodID(activity, "getPackageName", "()Ljava/lang/String;");
    jstring name_str = static_cast<jstring >( env->CallObjectMethod(context, methodId_pack));
    return Jstring2CStr(env,name_str);
}

jobject getApplication(JNIEnv *env) {
    jobject application = NULL;
    jclass activity_thread_clz = env->FindClass("android/app/ActivityThread");
    if (activity_thread_clz != NULL) {
        jmethodID get_Application = env->GetStaticMethodID(activity_thread_clz,
                                                           "currentActivityThread",
                                                           "()Landroid/app/ActivityThread;");
        if (get_Application != NULL) {
            jobject currentActivityThread = env->CallStaticObjectMethod(activity_thread_clz,
                                                                        get_Application);
            jmethodID getal = env->GetMethodID(activity_thread_clz, "getApplication",
                                               "()Landroid/app/Application;");
            application = env->CallObjectMethod(currentActivityThread, getal);
        }
        env->DeleteLocalRef(activity_thread_clz);
        return application;
    }
    env->DeleteLocalRef(activity_thread_clz);
    return application;
}


void save_shared_preferences(JNIEnv* env, jstring key, jint value) {
        const char* keyStr = env->GetStringUTFChars(key, NULL);

    // 获取上下文对象
    jobject context = getApplication(env); // 获取上下文对象

    // 获取Shared Preferences对象
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getSharedPreferencesMethod = env->GetMethodID(contextClass, "getSharedPreferences", "(Ljava/lang/String;I)Landroid/content/SharedPreferences;");
    jobject sharedPreferences = env->CallObjectMethod(context, getSharedPreferencesMethod, env->NewStringUTF("my_preferences"), 0);

    // 存储值
    jclass sharedPreferencesClass = env->GetObjectClass(sharedPreferences);
    jmethodID editMethod = env->GetMethodID(sharedPreferencesClass, "edit", "()Landroid/content/SharedPreferences$Editor;");
    jclass editorClass = env->FindClass("android/content/SharedPreferences$Editor");
    jobject editor = env->CallObjectMethod(sharedPreferences, editMethod);
    jmethodID putIntMethod = env->GetMethodID(editorClass, "putInt", "(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;");
    env->CallObjectMethod(editor, putIntMethod, env->NewStringUTF(keyStr), value);
    jmethodID commitMethod = env->GetMethodID(editorClass, "commit", "()Z");
    env->CallBooleanMethod(editor, commitMethod);

    // 释放资源
    env->ReleaseStringUTFChars(key, keyStr);
    env->DeleteLocalRef(editorClass);
    env->DeleteLocalRef(sharedPreferences);
    env->DeleteLocalRef(editor);
}

jint get_shared_preferences(JNIEnv* env, jstring key, jint defaultValue) {
    const char* keyStr = env->GetStringUTFChars(key, NULL);

    // 获取上下文对象
    jobject context = getApplication(env); // 获取上下文对象

    // 获取Shared Preferences对象
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getSharedPreferencesMethod = env->GetMethodID(contextClass, "getSharedPreferences", "(Ljava/lang/String;I)Landroid/content/SharedPreferences;");
    jobject sharedPreferences = env->CallObjectMethod(context, getSharedPreferencesMethod, env->NewStringUTF("my_preferences"), 0);

    // 获取存储的值
    jclass sharedPreferencesClass = env->GetObjectClass(sharedPreferences);
    jmethodID getIntMethod = env->GetMethodID(sharedPreferencesClass, "getInt", "(Ljava/lang/String;I)I");
    jint value = env->CallIntMethod(sharedPreferences, getIntMethod, env->NewStringUTF(keyStr), defaultValue);

    // 释放资源
    env->ReleaseStringUTFChars(key, keyStr);
    env->DeleteLocalRef(sharedPreferences);

    return value;
}
