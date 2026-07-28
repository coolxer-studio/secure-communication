package com.abc.demo.controller;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping(value = {"/v1", "/1"})
public class MessageController {

    @RequestMapping(value = {"/ping"})
    public String test(@RequestHeader Map<String, String> header, @RequestBody String strBody) {
        String token = header.get("scid");
        return "server received: scid->"+token+",strBody->"+strBody;
    }

    @RequestMapping(value = {"/1", "/2"})
    public String helloSc01(HttpServletRequest request, HttpServletResponse response,
                            @RequestHeader Map<String, String> header, @RequestBody String strBody) {
        for (String key : header.keySet()) {
            System.out.println("header=" + key + ":" + header.get(key));
        }
        System.out.println("body  =" + strBody);
        return "hello sc2";
    }

    @RequestMapping(value = {"/a", "/b"})
    public String helloSc02(HttpServletRequest request, HttpServletResponse response) {
        request.getHeaderNames();
        response.getHeaderNames();
        return "hello sc2";
    }


}
