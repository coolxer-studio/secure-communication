package com.abc.sc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.HashSet;

@ConfigurationProperties(prefix = "spring.sc")
@Data
public class ScServiceProperties {

    private boolean enabled;
    private String prefix = "/sc";
    private String reservePrefix = "/reserve/";
    private String h5Prefix = "/h5/";
    private int repeatQueueSize = 1000;
    private UrlObfuscate urlObfuscate = new UrlObfuscate(false,"","");
    private Encryption encryption = new Encryption(false,"sm4","CBC","7dFg7SFKEVIND4fD","WIAw89fW6bFh9WsS");
    private HashSet<String> identify = new HashSet<String>(Arrays.asList());

    @Data
    public static class UrlObfuscate{
        private boolean enabled;
        private String model;
        private String key;

        public UrlObfuscate(boolean enabled, String model, String key) {
            this.enabled = enabled;
            this.model = model;
            this.key = key;
        }
    }

    @Data
    public static class Encryption{
        private boolean enabled;
        private String algorithm;
        private String mode;
        private String key;
        private String iv;

        public Encryption(boolean enabled, String algorithm, String mode, String key, String iv) {
            this.enabled = enabled;
            this.algorithm = algorithm;
            this.mode = mode;
            this.key = key;
            this.iv = iv;
        }
    }

}
