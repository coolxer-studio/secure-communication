package com.abc.sc.encrypt;

import com.abc.sc.encrypt.js.JsSM4Utils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsSM4UtilTest {

    private static final int APP_ID_LENGTH = 32;
    private static final String REQUEST_BODY = "697FAF50DE96323E543C690263B3BE064B29AC056D83B61971CA85D3DD068C1758DC5ADC82421B6401E7404225377C9B724A95BD5E223C7779896930A96365AC59F74D8E370B7E4075EACF13AEA2A3CE3EB66585D25408D215EC7586374255C7271EC0B0FA42F14852872A5866E90CCD3874101CF78B99450F4749CE53F960E19D0E7404B2D88AF63D9FEA996378F5AF7D4DFCE4984F0ADB15838F386C19EC0450BFB0D938451462226B82410012EB49EAFBC9D1C0F7AB17B9D8950C6191B43C6DD4C91D6357728362E7CE92EC4EFD541596861234C4EA6DDD041D45B3912345";
    private static final String EXPECTED_PLAIN_TEXT = "{\"common\":[\"7b5b925a-0cd5-42f4-a64f-5ceba73d6fed\",\"function getTime() { [native code] }\",\"1.0.0\",\"1\",\"测试web\",\"\",\"-\",\"h5\",\"-\",\"-\",\"-\",\"-\",\"其他网络\",\"-\",\"-\",null,null,\"-\",\"2023-08-10T01:52:23.834Z\"]}";

    @Test
    void h5RequestVectorIsCompatibleWithJavaScriptSdk() throws DecoderException {
        String cipherHex = REQUEST_BODY.substring(0, REQUEST_BODY.length() - APP_ID_LENGTH);
        String appId = REQUEST_BODY.substring(REQUEST_BODY.length() - APP_ID_LENGTH);
        String token = appId.toLowerCase(Locale.ROOT) + "_bsdk_";
        String key = DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        JsSM4Utils sm4 = new JsSM4Utils(key, key);

        byte[] decrypted = sm4.decryptDataCBC(Hex.decodeHex(cipherHex));

        assertNotNull(decrypted);
        assertEquals(EXPECTED_PLAIN_TEXT, new String(decrypted, StandardCharsets.UTF_8));
        assertEquals(
                cipherHex.toLowerCase(Locale.ROOT),
                Hex.encodeHexString(sm4.encryptDataCBC(EXPECTED_PLAIN_TEXT.getBytes(StandardCharsets.UTF_8)))
        );
    }
}
