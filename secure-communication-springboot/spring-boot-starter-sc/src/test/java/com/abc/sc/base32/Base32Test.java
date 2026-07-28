package com.abc.sc.base32;

import com.abc.sc.encrypt.SM4Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Base32Test {

    private static String INPUT_STR = "hello sc";
    private static String OUTPUT_STR = "NBSWY3DPEBZWG";

    @Test
    void encode() {
        String encoded = Base32.encode(INPUT_STR.getBytes());
        Assertions.assertEquals(OUTPUT_STR, encoded);
    }

    @Test
    void decode() {
        String decodeed = new String(Base32.decode(OUTPUT_STR));
        Assertions.assertEquals(INPUT_STR, decodeed);
    }

    @Test
    void encodeUrl(){
        String key = "dsfasdfladsjrflk";
        String uri = "/1/1?name=yaoqi&age=20&name=lisi";
        String encoded = Base32.encode(new SM4Utils(key).encryptDataECB(uri.getBytes()));
        Assertions.assertEquals("ZRDW2VPAGS4QJJTBHWFSB4ZUD5ZUBTJSQY5FMSYI3AI2AEDONHSXHOLKEIWKFTARDRA4Z66IGHBKQ",encoded);
    }
}