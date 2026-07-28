package com.abc.sc.encrypt.js;

import java.util.Locale;

public class JsSM4Utils {

    private String secretKey = "";
    private String iv = "";
    private boolean hexString = false;

    public JsSM4Utils(String secretKey, String iv, boolean hexString) {
        this.secretKey = secretKey;
        this.iv = iv;
        this.hexString = hexString;
    }

    public JsSM4Utils(String secretKey, String iv) {
        this.secretKey = secretKey;
        this.iv = iv;
    }

    public JsSM4Utils(String secretKey) {
        this.secretKey = secretKey;
    }

    public byte[] encryptDataECB(byte[] plainText) {
        try {
            SM4Context ctx = new SM4Context();
            ctx.isPadding = true;
            ctx.mode = SM4.SM4_ENCRYPT;
            byte[] keyBytes;
            if (hexString) {
                keyBytes = Utils.hexStringToBytes(secretKey);
            } else {
                keyBytes = secretKey.getBytes();
            }
            SM4 sm4 = new SM4();
            sm4.sm4SetkeyEnc(ctx, keyBytes);
            byte[] encrypted = sm4.sm4CryptEcb(ctx, plainText);
            return encrypted;
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] decryptDataECB(byte[] cipherText) {
        try {
            SM4Context ctx = new SM4Context();
            ctx.isPadding = true;
            ctx.mode = SM4.SM4_DECRYPT;
            byte[] keyBytes;
            if (hexString) {
                keyBytes = Utils.hexStringToBytes(secretKey);
            } else {
                keyBytes = secretKey.getBytes();
            }
            SM4 sm4 = new SM4();
            sm4.sm4SetkeyDec(ctx, keyBytes);
            byte[] decrypted = sm4.sm4CryptEcb(ctx, cipherText);
            return decrypted;
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] encryptDataCBC(byte[] plainText) {
        try {
            SM4Context ctx = new SM4Context();
            ctx.isPadding = true;
            ctx.mode = SM4.SM4_ENCRYPT;
            byte[] keyBytes;
            byte[] ivBytes;
            if (hexString) {
                keyBytes = Utils.hexStringToBytes(secretKey);
                ivBytes = Utils.hexStringToBytes(iv);
            } else {
                keyBytes = secretKey.getBytes();
                ivBytes = iv.getBytes();
            }

            SM4 sm4 = new SM4();
            sm4.sm4SetkeyEnc(ctx, keyBytes);
            byte[] encrypted = sm4.sm4CryptCbc(ctx, ivBytes, plainText);
            return encrypted;
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] decryptDataCBC(byte[] cipherText) {
        try {
            SM4Context ctx = new SM4Context();
            ctx.isPadding = true;
            ctx.mode = SM4.SM4_DECRYPT;
            byte[] keyBytes;
            byte[] ivBytes;
            if (hexString) {
                keyBytes = Utils.hexStringToBytes(secretKey);
                ivBytes = Utils.hexStringToBytes(iv);
            } else {
                keyBytes = secretKey.getBytes();
                ivBytes = iv.getBytes();
            }
            SM4 sm4 = new SM4();
            sm4.sm4SetkeyDec(ctx, keyBytes);
            byte[] decrypted = sm4.sm4CryptCbc(ctx, ivBytes, cipherText);
            return decrypted;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    // 内部工具类
    private static class Utils {
        /**
         * Convert hex string to byte[]
         *
         * @param hexString the hex string
         * @return byte[]
         */
        protected static byte[] hexStringToBytes(String hexString) {
            if (hexString == null || "".equals(hexString)) {
                return null;
            }

            hexString = hexString.toUpperCase(Locale.ROOT);
            int length = hexString.length() / 2;
            char[] hexChars = hexString.toCharArray();
            byte[] d = new byte[length];
            for (int i = 0; i < length; i++) {
                int pos = i * 2;
                d[i] = (byte) (charToByte(hexChars[pos]) << 4 | charToByte(hexChars[pos + 1]));
            }
            return d;
        }

        /**
         * Convert char to byte
         */
        private static byte charToByte(char c) {
            return (byte) "0123456789ABCDEF".indexOf(c);
        }
    }

}
