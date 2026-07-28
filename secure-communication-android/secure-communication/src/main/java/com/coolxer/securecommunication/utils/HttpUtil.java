package com.coolxer.securecommunication.utils;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

public class HttpUtil {

    public static String sendGetRequest(String urlString, String headerString) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            // 设置 Header 头参数
            if (headerString != null && !headerString.isEmpty()) {
                String[] headerArray = headerString.replaceAll("\r\n","\n").split("\n");
                for (String header:headerArray) {
                    String[] headerKeyValue = header.split(":");
                    if(headerKeyValue.length>1){
                        connection.setRequestProperty(headerKeyValue[0], headerKeyValue[1]);
                    }
                }
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    public static String sendPostRequest(String urlString, String headerString, String requestBody) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            // 设置 Header 头参数
            if (headerString != null && !headerString.isEmpty()) {
                String[] headerArray = headerString.replaceAll("\r\n","\n").split("\n");
                for (String header:headerArray) {
                    String[] headerKeyValue = header.split(":");
                    if(headerKeyValue.length>1){
                        connection.setRequestProperty(headerKeyValue[0], headerKeyValue[1]);
                    }
                }
            }

            if (requestBody!=null && !TextUtils.isEmpty(requestBody)) {
                OutputStream outputStream = connection.getOutputStream();
                outputStream.write(requestBody.getBytes());
                outputStream.flush();
                outputStream.close();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }else{
                Log.d("Http", "responseCode: "+responseCode);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    public static String decode(String base64){
        if (base64==null || base64.isEmpty()){
            return base64;
        }
        String decodedString = null;
        try {
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

            byte[] decodedData = Base64.decode(sb.toString(), Base64.DEFAULT);

            // 将解码后的字节数组转换为字符串
            decodedString = new String(decodedData);
        } catch (Exception e) {
            e.printStackTrace();
            return base64;
        }

        return decodedString;
    }

    public static String encode(String value){
        if (value==null || value.isEmpty()){
            return value;
        }
        String base64 = Base64.encodeToString(value.getBytes(), Base64.DEFAULT);
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

    public static String encodeUri(String uri){
        // 针对url的特殊字符替换
        String newUri = new Random().nextInt(1000)+uri;
        String base64 = encode(newUri);
        String protectBase64 = base64.replaceAll("\\+","!").replaceAll("/","@").replaceAll("=","\\*");
        return protectBase64;
    }
}
