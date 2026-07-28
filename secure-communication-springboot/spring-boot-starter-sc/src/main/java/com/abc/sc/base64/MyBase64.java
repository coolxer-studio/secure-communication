package com.abc.sc.base64;

import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.util.StringUtils;

public class MyBase64 {

    public static String decode(String base64){
        if (StringUtils.isEmpty(base64)){
            return base64;
        }
        // 针对url的特殊字符替换
        String realBase64 = base64.replaceAll("!","+").replaceAll("@","/").replaceAll("\\*","=");
        StringBuilder sb = new StringBuilder();
        for (char c:realBase64.toCharArray()) {
            char realChar = c;
            if(Character.isLowerCase(c)){
                realChar = Character.toUpperCase(c);
            }else if (Character.isUpperCase(c)){
                realChar = Character.toLowerCase(c);
            }
            sb.append(realChar);
        }
        return new String(Base64.decodeBase64(sb.toString()));
    }

    public static String encode(String value){
        String base64 = Base64.encodeBase64String(value.getBytes());
        StringBuilder sb = new StringBuilder();
        for (char c:base64.toCharArray()) {
            char realChar = c;
            if(Character.isLowerCase(c)){
                realChar = Character.toUpperCase(c);
            }else if (Character.isUpperCase(c)){
                realChar = Character.toLowerCase(c);
            }
            sb.append(realChar);
        }
        return sb.toString();
    }
}
