package com.coolxer.securecommunication;

public class CTSecureCommunication {
    static {
       System.loadLibrary("secure-communication");
    }
    public static native String get(String uri, String header);
    public static native String post(String uri, String header, String body);

}
