package com.abc.sc.encrypt;

import com.abc.sc.encrypt.js.JsSM4Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.io.UnsupportedEncodingException;

@Slf4j
public class JsSM4UtilTest {

    @Test
    void custom1() throws UnsupportedEncodingException, DecoderException {
        String requestBody = "697FAF50DE96323E543C690263B3BE064B29AC056D83B61971CA85D3DD068C1758DC5ADC82421B6401E7404225377C9B724A95BD5E223C7779896930A96365AC59F74D8E370B7E4075EACF13AEA2A3CE3EB66585D25408D215EC7586374255C7271EC0B0FA42F14852872A5866E90CCD3874101CF78B99450F4749CE53F960E19D0E7404B2D88AF63D9FEA996378F5AF7D4DFCE4984F0ADB15838F386C19EC0450BFB0D938451462226B82410012EB49EAFBC9D1C0F7AB17B9D8950C6191B43C6DD4C91D6357728362E7CE92EC4EFD541596861234C4EA6DDD041D45B3912345";
        // 获取accept_hex用于计算加密key.
        int keyLength = 32;
        String encodeString = requestBody.substring(0,requestBody.length() - keyLength);
        String acceptHex = requestBody.substring(requestBody.length() - keyLength);
        // 现在使用了hex编码，这需要解码.
        byte[] decodeBytes = Hex.decodeHex(encodeString);
        String _BSDK_ = "_bsdk_";
        String token = acceptHex.toLowerCase() + _BSDK_;
        String md5 = DigestUtils.md5DigestAsHex(token.getBytes());
        String key = md5.substring(0, 16);
        String iv = key;
        byte[] decryptBytes = new JsSM4Utils(key,iv).decryptDataCBC(decodeBytes);
        if (decodeBytes == null) {
            // 解密后内容为空.
            log.debug("[receive message] after decrypt body is null");
        }
        String lastBody = new String(decryptBytes);
        log.debug("lastBody:"+lastBody);

        String msg = "[\"0\",\"\",\"./bs.dev.js\",\"http://172.16.22.162:8280/everisk/api/v4/receiver/h5/upload\",0,1,0,\"0\",60,10,[\"_webaction\",\"_webperformance\"],[1,2],[\"dom,window\",\"af\"],[\"sensor,mousemove,domain,appbind,error,click,touchstart,touchend,pageshow,pagehide,input,debug,camera,mousedown,mouseup\",\"sys\"],[[\"cookie\"]],\"65ccea2e0f75b84e9920268ddebfcb9f8fea29026be1addad973edfe6e0efac2fd3632bd60d07cfae34e2fea856a9ab961acc275f54882a6b8bc26c32c07e6ff\",\"\",\"\",0,\"http://172.16.22.162:8280/everisk/api/v4/receiver/h5/fingerprint\",\"https://h5.bangcle.com:8896/everisk/api/v4/devmark/h5/information\",[\"https://verify.cmpassport.com/h5/getMobile\",\"mfawsxtcmyplwzpayzzvdvbsowxmkynr\",\"7918DFACC911F0CF0722865F5BE655F7\",\"300012035411\",\"redbyxsdetddwaaffajcwwapspykftzx\",\"3\",\"1.0\"],0,0,100,10]";
        log.debug("response:"+Hex.encodeHexString(new JsSM4Utils(key,iv).encryptDataCBC(msg.getBytes())));
    }



}
