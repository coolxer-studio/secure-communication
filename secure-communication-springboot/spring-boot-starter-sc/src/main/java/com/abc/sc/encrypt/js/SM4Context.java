package com.abc.sc.encrypt.js;

class SM4Context {

    public int mode;
    public int[] sk;
    public boolean isPadding;

    public SM4Context() {
        this.mode = 1;
        this.isPadding = true;
        this.sk = new int[32];
    }
}
