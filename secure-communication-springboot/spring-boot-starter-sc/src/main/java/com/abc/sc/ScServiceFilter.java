package com.abc.sc;

import com.abc.sc.base32.Base32;
import com.abc.sc.base64.MyBase64;
import com.abc.sc.constant.CONST;
import com.abc.sc.constant.ERROR;
import com.abc.sc.encrypt.SM4Utils;
import com.abc.sc.encrypt.js.JsSM4Utils;
import com.abc.sc.servlet.ScRequestWrapper;
import com.abc.sc.servlet.ScResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.events.Event;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


// 定义filterName 和过滤的url
@Slf4j
@WebFilter(filterName = "scServiceFilter", urlPatterns = "/*")
public class ScServiceFilter implements Filter {
    private ScServiceProperties scServiceProperties;

    public ScServiceFilter(ScServiceProperties scServiceProperties) {
        this.scServiceProperties = scServiceProperties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        String originalUri = httpServletRequest.getRequestURI();
        if (originalUri.startsWith(scServiceProperties.getPrefix())) {
            if (originalUri.startsWith(scServiceProperties.getPrefix()+scServiceProperties.getH5Prefix())){
                // h5定制的处理逻辑
                // 不支持判断重复防止重放
                // 提取uri和参数
                ParameUriModelForH5 parameUriModel = new ParameUriModelForH5(originalUri);
                // 不支持检查标识(uri中提取)
                // 提取解密body（自定义）
                String originalBody = getBodyString(httpServletRequest);
                // 自定义
                // 获取accept_hex用于计算加密key.
                int keyLength = 32;
                String key = null;
                String iv = null;
                String newBody = originalBody;
                if(originalBody.length()>32){
                    String encodeString = originalBody.substring(0,originalBody.length() - keyLength);
                    String acceptHex = originalBody.substring(originalBody.length() - keyLength);
                    // 现在使用了hex编码，这需要解码.
                    byte[] decodeBytes = new byte[0];
                    try {
                        decodeBytes = Hex.decodeHex(encodeString);
                    } catch (DecoderException e) {
                        e.printStackTrace();
                    }
                    String token = acceptHex.toLowerCase() + "_bsdk_";
                    String md5 = DigestUtils.md5DigestAsHex(token.getBytes());
                    key = md5.substring(0, 16);
                    iv = key;
                    byte[] decryptBytes = new JsSM4Utils(key,iv).decryptDataCBC(decodeBytes);
                    if (decodeBytes == null) {
                        // 解密后内容为空.
                        log.debug("[receive message] after decrypt body is null");
                    }
                    newBody = new String(decryptBytes);
                }
                // debug info
                log.debug("{},{},{}->{},{},{}", originalUri, httpServletRequest.getParameterMap(), originalBody, parameUriModel.newUri, parameUriModel.newParameters, newBody);
                // 创建装饰器，执行
                ScRequestWrapper scRequestWrapper = new ScRequestWrapper(httpServletRequest, parameUriModel.newUri, parameUriModel.newParameters, newBody);
                ScResponseWrapper scResponseWrapper = new ScResponseWrapper(httpServletResponse);
                chain.doFilter(scRequestWrapper, scResponseWrapper);
                // 对response的body提取并且更改
                String responseContent = scResponseWrapper.getResponseContent();
                // 自定义
                String newContent = responseContent;
                if(key!=null && iv !=null){
                    newContent =  Hex.encodeHexString(new JsSM4Utils(key,iv).encryptDataCBC(responseContent.getBytes()));
                }
                log.debug("{}修改为：{}", responseContent, newContent);
                response.setContentLength(newContent.length());// 设置-1的时候response内容会莫名的多一些字符
                PrintWriter out = response.getWriter();
                out.write(newContent);
                out.flush();
                out.close();
            }else if (originalUri.startsWith(scServiceProperties.getPrefix()+scServiceProperties.getReservePrefix())){
                // 备用接口
                // 不支持判断重复防止重放
                // 提取uri和参数
                ParameUriModelForReserve parameUriModel = new ParameUriModelForReserve(originalUri);
                // 不支持检查标识(uri中提取)
                // 提取解密body（自定义）
                String originalBody = getBodyString(httpServletRequest);
                // 自定义
                String newBody = MyBase64.decode(originalBody);
                // debug info
                log.debug("{},{},{}->{},{},{}", originalUri, httpServletRequest.getParameterMap(), originalBody, parameUriModel.newUri, parameUriModel.newParameters, newBody);
                // 创建装饰器，执行
                ScRequestWrapper scRequestWrapper = new ScRequestWrapper(httpServletRequest, parameUriModel.newUri, parameUriModel.newParameters, newBody);
                ScResponseWrapper scResponseWrapper = new ScResponseWrapper(httpServletResponse);
                chain.doFilter(scRequestWrapper, scResponseWrapper);
                // 对response的body提取并且更改
                String responseContent = scResponseWrapper.getResponseContent();
                // 自定义
                String newContent =  MyBase64.encode(responseContent);
                log.debug("{}修改为：{}", responseContent, newContent);
                response.setContentLength(newContent.length());// 设置-1的时候response内容会莫名的多一些字符
                PrintWriter out = response.getWriter();
                out.write(newContent);
                out.flush();
                out.close();
            }else{
                // 判断重复防止重放
                if (scServiceProperties.getRepeatQueueSize() > 0 && repeatURI(originalUri)) {
                    returnText(response, ERROR.REPEAT_URI);
                    return;
                }
                // 提取uri和参数
                ParameUriModel parameUriModel = new ParameUriModel(originalUri);
                // 是否检查标识(uri中提取)
                if (scServiceProperties.getIdentify().size() > 0) {
                    int IDindex = parameUriModel.newUri.indexOf(CONST.ID);
                    if (IDindex == -1) {
                        returnText(response, ERROR.UNKNOW_IDENTIFY);
                        return;
                    } else {
                        String identifyStr = parameUriModel.newUri.substring(0, IDindex);
                        if (scServiceProperties.getIdentify().contains(identifyStr)) {
                            parameUriModel.newUri = parameUriModel.newUri.substring(IDindex + CONST.ID_LEN);
                        } else {
                            returnText(response, ERROR.UNKNOW_IDENTIFY);
                            return;
                        }
                    }
                }else{
                    // 不需要检查标识(但是客户端上传了标识，需要截取)
                    int IDindex = parameUriModel.newUri.indexOf(CONST.ID);
                    if (IDindex != -1) {
                        parameUriModel.newUri = parameUriModel.newUri.substring(IDindex + CONST.ID_LEN);
                    }
                }
                // 提取解密body
                String originalBody = getBodyString(httpServletRequest);
                String newBody = decriptAndEncrptBody(originalBody, true);
                // debug info
                log.debug("{},{},{}->{},{},{}", originalUri, httpServletRequest.getParameterMap(), originalBody, parameUriModel.newUri, parameUriModel.newParameters, newBody);
                // 创建装饰器，执行
                ScRequestWrapper scRequestWrapper = new ScRequestWrapper(httpServletRequest, parameUriModel.newUri, parameUriModel.newParameters, newBody);
                ScResponseWrapper scResponseWrapper = new ScResponseWrapper(httpServletResponse);
                chain.doFilter(scRequestWrapper, scResponseWrapper);
                // 对response的body提取并且更改
                String responseContent = scResponseWrapper.getResponseContent();
                if(responseContent==null || responseContent.length()==0){
                    responseContent = String.valueOf(scResponseWrapper.getStatus());
                }
                String newContent = decriptAndEncrptBody(responseContent, false);
                log.debug("{}修改为：{}", responseContent, newContent);
                response.setContentLength(newContent.length());// 设置-1的时候response内容会莫名的多一些字符
                PrintWriter out = response.getWriter();
                out.write(newContent);
                out.flush();
                out.close();
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("{}-init", this.getClass().getName());
    }

    @Override
    public void destroy() {
        log.info("{}-destroy", this.getClass().getName());
    }

    private abstract class BaseParameUriModel {
        public String newUri;
        public HashMap<String, String[]> newParameters = new HashMap<String, String[]>();
    }
    private class ParameUriModel extends  BaseParameUriModel{

        public ParameUriModel(String originalUri) {
            // 解密uri
            String obfuscateStr = originalUri.substring(originalUri.lastIndexOf("/") + 1);
            StringBuffer encodeKey = new StringBuffer();
            StringBuffer encodeStr = new StringBuffer();
            for (int tmp : obfuscateStr.getBytes()) {
                if (tmp > 96 && tmp < 123) {
                    encodeKey.append((char) tmp);
                } else {
                    encodeStr.append((char) tmp);
                }
            }
            String paramUri = null;
            try {
                paramUri = new String(new SM4Utils(encodeKey.toString()).decryptDataECB(Base32.decode(encodeStr.toString())));
            } catch (Exception e) {
                log.error("decript failed ! originalUri:{},{}", originalUri, e);
            }
            if (paramUri == null) {
                this.newUri = originalUri;
            } else {
                String[] paramUriArray = paramUri.split("\\?");
                //提取uri
                this.newUri = paramUriArray[0];
                // 提取parames
                HashMap<String, ArrayList<String>> newParameListHash = new HashMap<String, ArrayList<String>>();
                if (paramUriArray.length > 1) {
                    String[] paramStrArray = paramUriArray[1].split("&");
                    for (String paramStr : paramStrArray) {
                        String[] keyValue = paramStr.split("=");
                        String key = keyValue[0];
                        if (newParameListHash.containsKey(key)) {
                            newParameListHash.get(key).add(keyValue.length > 1 ? keyValue[1] : "");
                        } else {
                            ArrayList<String> valueList = new ArrayList<String>();
                            valueList.add(keyValue.length > 1 ? keyValue[1] : "");
                            newParameListHash.put(key, valueList);
                        }
                    }
                    for (Map.Entry<String, ArrayList<String>> entry : newParameListHash.entrySet()) {
                        this.newParameters.put(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                }
            }
        }
    }

    private class ParameUriModelForReserve extends BaseParameUriModel{

        public ParameUriModelForReserve(String originalUri) {
            String base64Uri = originalUri.split(scServiceProperties.getReservePrefix())[1];
            String paramUri = MyBase64.decode(base64Uri);
            if (paramUri == null) {
                this.newUri = originalUri;
            } else {
                paramUri = paramUri.substring(paramUri.indexOf("/"));
                String[] paramUriArray = paramUri.split("\\?");
                //提取uri
                this.newUri = paramUriArray[0];
                // 提取parames
                HashMap<String, ArrayList<String>> newParameListHash = new HashMap<String, ArrayList<String>>();
                if (paramUriArray.length > 1) {
                    String[] paramStrArray = paramUriArray[1].split("&");
                    for (String paramStr : paramStrArray) {
                        String[] keyValue = paramStr.split("=");
                        String key = keyValue[0];
                        if (newParameListHash.containsKey(key)) {
                            newParameListHash.get(key).add(keyValue.length > 1 ? keyValue[1] : "");
                        } else {
                            ArrayList<String> valueList = new ArrayList<String>();
                            valueList.add(keyValue.length > 1 ? keyValue[1] : "");
                            newParameListHash.put(key, valueList);
                        }
                    }
                    for (Map.Entry<String, ArrayList<String>> entry : newParameListHash.entrySet()) {
                        this.newParameters.put(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                }
            }
        }

    }

    private class ParameUriModelForH5 extends BaseParameUriModel{

        public ParameUriModelForH5(String originalUri) {
            String paramUri = originalUri.split(scServiceProperties.getH5Prefix())[1];
            if (paramUri == null || paramUri.length() == 0) {
                this.newUri = originalUri;
            } else {
                paramUri = "/"+paramUri;
                String[] paramUriArray = paramUri.split("\\?");
                //提取uri
                this.newUri = paramUriArray[0];
                // 提取parames
                HashMap<String, ArrayList<String>> newParameListHash = new HashMap<String, ArrayList<String>>();
                if (paramUriArray.length > 1) {
                    String[] paramStrArray = paramUriArray[1].split("&");
                    for (String paramStr : paramStrArray) {
                        String[] keyValue = paramStr.split("=");
                        String key = keyValue[0];
                        if (newParameListHash.containsKey(key)) {
                            newParameListHash.get(key).add(keyValue.length > 1 ? keyValue[1] : "");
                        } else {
                            ArrayList<String> valueList = new ArrayList<String>();
                            valueList.add(keyValue.length > 1 ? keyValue[1] : "");
                            newParameListHash.put(key, valueList);
                        }
                    }
                    for (Map.Entry<String, ArrayList<String>> entry : newParameListHash.entrySet()) {
                        this.newParameters.put(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                }
            }
        }

    }

    /**
     * 获取请求Body
     *
     * @param request
     * @return
     */
    private static String getBodyString(ServletRequest request) {
        StringBuilder sb = new StringBuilder();
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            inputStream = request.getInputStream();
            reader = new BufferedReader(new InputStreamReader(inputStream, Charset.forName("UTF-8")));
            String line = "";
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return sb.toString();
    }

    // 加解密body
    private String decriptAndEncrptBody(String originalBody, boolean decript) {
        if (!StringUtils.hasLength(originalBody)) {
            // null or "" return originalBody
            return originalBody;
        }
        SM4Utils sm4Utils = new SM4Utils(scServiceProperties.getEncryption().getKey(), scServiceProperties.getEncryption().getIv());
        try {
            if ("CBC".equals(scServiceProperties.getEncryption().getMode())) {
                if (decript) {
                    return new String(sm4Utils.decryptDataCBC(Base64.getDecoder().decode(originalBody)));
                } else {
                    return Base64.getEncoder().encodeToString(sm4Utils.encryptDataCBC(originalBody.getBytes()));
                }
            } else {
                // ECB no need to get iv
                if (decript) {
                    return new String(sm4Utils.decryptDataECB(Base64.getDecoder().decode(originalBody)));
                } else {
                    return Base64.getEncoder().encodeToString(sm4Utils.encryptDataECB(originalBody.getBytes()));
                }
            }
        } catch (Exception e) {
            log.error("decript failed ! originalBody:{},{}", originalBody, e);
        }
        // decript failed return originalBody
        return originalBody;
    }

    // 已执行uri队列
    private static final ConcurrentLinkedQueue<String> repeatQueue = new ConcurrentLinkedQueue<String>();
    // 支持延时的线程池，防止时间因子的随机密钥在短时间内相同导致被拒绝的问题
    ScheduledExecutorService repeatScheduledThreadPool = Executors.newScheduledThreadPool(10);
    private boolean repeatURI(String uri) {
        if (repeatQueue.contains(uri)) {
            return true;
        } else {
            repeatScheduledThreadPool.schedule(new Runnable() {
                @Override
                public void run() {
                    int repeatQueueSize = repeatQueue.size();
                    if (repeatQueueSize > scServiceProperties.getRepeatQueueSize()) {
                        String pollUri = repeatQueue.poll();
                        log.debug("repeatQueue size:{},{}", repeatQueueSize, pollUri);
                    }
                    repeatQueue.offer(uri);
                }
            }, 10, TimeUnit.SECONDS);
            return false;
        }
    }

    private void returnText(ServletResponse response, String text) {
        PrintWriter writer = null;
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain;charset=UTF-8");
        try {
            writer = response.getWriter();
            writer.print(text);

        } catch (IOException e) {
            log.error("response error", e);
        } finally {
            if (writer != null){
                writer.close();
            }
        }
    }

}
