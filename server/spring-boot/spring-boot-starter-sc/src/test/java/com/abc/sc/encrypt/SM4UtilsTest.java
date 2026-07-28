package com.abc.sc.encrypt;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Base64;

class SM4UtilsTest {

    private static String TEST_STR = "hello sc";
    private static String TEST_STR_DECRIPT_CBC = "9Rlg9LwIVAxViItqjMwKyw==";
    private static String TEST_STR_DECRIPT_ECB = "NRnlEdx1OTIMtSq6OyAQug==";
    private static final String KEY = "7dFg7SFKEVIND4fD";
    private static final String IV = "WIAw89fW6bFh9WsS";

    @Test
    void encryptDataECB() {
        byte[] encript = new SM4Utils(KEY).encryptDataECB(TEST_STR.getBytes());
        String encriptString = Base64.getEncoder().encodeToString(encript);
        Assertions.assertEquals(TEST_STR_DECRIPT_ECB, encriptString);
    }

    /**
     * 字节数组转16进制
     * @param bytes 需要转换的byte数组
     * @return  转换后的Hex字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuffer sb = new StringBuffer();
        for(int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(bytes[i] & 0xFF);
            if(hex.length() < 2){
                sb.append(0);
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * Hex字符串转byte
     * @param inHex 待转换的Hex字符串
     * @return  转换后的byte
     */
    public static byte hexToByte(String inHex){
        return (byte)Integer.parseInt(inHex,16);
    }

    /**
     * hex字符串转byte数组
     * @param inHex 待转换的Hex字符串
     * @return  转换后的byte数组结果
     */
    public static byte[] hexToByteArray(String inHex){
        int hexlen = inHex.length();
        byte[] result;
        if (hexlen % 2 == 1){
            //奇数
            hexlen++;
            result = new byte[(hexlen/2)];
            inHex="0"+inHex;
        }else {
            //偶数
            result = new byte[(hexlen/2)];
        }
        int j=0;
        for (int i = 0; i < hexlen; i+=2){
            result[j]=hexToByte(inHex.substring(i,i+2));
            j++;
        }
        return result;
    }

    @Test
    void decryptDataECB() {
        byte[] a = {(byte) 113, (byte) 223, (byte) 228, (byte) 86, (byte) 191, (byte) 217, (byte) 6, (byte) 180, (byte) 168, (byte) 125, (byte) 80, (byte) 148, (byte) 219, (byte) 9, (byte) 33,(byte) 29};
        String dd = bytesToHex(a);
        byte[] decript = new SM4Utils("soafurbuypzvygzv").decryptDataECB(a);
        Assertions.assertEquals(TEST_STR, new String(decript));
    }

    @Test
    void encryptDataCBC() {
        byte[] encript = new SM4Utils(KEY,IV).encryptDataCBC(TEST_STR.getBytes());
        String encriptString = Base64.getEncoder().encodeToString(encript);
        Assertions.assertEquals(TEST_STR_DECRIPT_CBC, encriptString);
    }

    @Test
    void decryptDataCBC() {
        byte[] decript = new SM4Utils(KEY,IV).decryptDataCBC(Base64.getDecoder().decode(TEST_STR_DECRIPT_CBC));
        Assertions.assertEquals(TEST_STR, new String(decript));
    }
}