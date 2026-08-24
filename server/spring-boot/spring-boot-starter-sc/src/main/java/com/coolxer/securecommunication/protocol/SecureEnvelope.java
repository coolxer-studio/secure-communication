package com.coolxer.securecommunication.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SecureEnvelope {
    private final int v;
    private final String suite;
    private final String kid;
    private final String sid;
    private final long ts;
    private final long seq;
    private final String rid;
    private final String m;
    private final String p;
    private final String cty;
    private final int st;
    private final String nonce;
    private final String ct;

    @JsonCreator
    public SecureEnvelope(
            @JsonProperty(value = "v", required = true) int v,
            @JsonProperty(value = "suite", required = true) String suite,
            @JsonProperty(value = "kid", required = true) String kid,
            @JsonProperty(value = "sid", required = true) String sid,
            @JsonProperty(value = "ts", required = true) long ts,
            @JsonProperty(value = "seq", required = true) long seq,
            @JsonProperty(value = "rid", required = true) String rid,
            @JsonProperty(value = "m", required = true) String m,
            @JsonProperty(value = "p", required = true) String p,
            @JsonProperty(value = "cty", required = true) String cty,
            @JsonProperty(value = "st", required = true) int st,
            @JsonProperty(value = "nonce", required = true) String nonce,
            @JsonProperty(value = "ct", required = true) String ct) {
        this.v = v;
        this.suite = suite;
        this.kid = kid;
        this.sid = sid;
        this.ts = ts;
        this.seq = seq;
        this.rid = rid;
        this.m = m;
        this.p = p;
        this.cty = cty;
        this.st = st;
        this.nonce = nonce;
        this.ct = ct;
    }

    public int getV() {
        return v;
    }

    public String getSuite() {
        return suite;
    }

    public String getKid() {
        return kid;
    }

    public String getSid() {
        return sid;
    }

    public long getTs() {
        return ts;
    }

    public long getSeq() {
        return seq;
    }

    public String getRid() {
        return rid;
    }

    public String getM() {
        return m;
    }

    public String getP() {
        return p;
    }

    public String getCty() {
        return cty;
    }

    public int getSt() {
        return st;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCt() {
        return ct;
    }
}
