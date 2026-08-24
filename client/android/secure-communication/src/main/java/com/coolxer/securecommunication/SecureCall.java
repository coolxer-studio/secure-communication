package com.coolxer.securecommunication;

public interface SecureCall {
    SecureResponse execute() throws SecureError;
    void enqueue(Callback callback);
    void cancel();
    boolean isCanceled();

    interface Callback {
        void onResponse(SecureResponse response);
        void onFailure(SecureError error);
    }
}
