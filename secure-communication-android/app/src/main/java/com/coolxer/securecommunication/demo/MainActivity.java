package com.coolxer.securecommunication.demo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.coolxer.securecommunication.CTSecureCommunication;

public class MainActivity extends AppCompatActivity {

    private Button testButton;
    private TextView textView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        testButton = findViewById(R.id.test);
        textView = findViewById(R.id.text);

        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // test code
//                String getInfo = CTSecureCommunication.get("/1/ping_get",null);
//                String getInfo = CTSecureCommunication.get("/1/ping_get","test:ddd\r\n");
//                textView.setText(getInfo);
                new Thread() {
                    @Override
                    public void run() {
                        super.run();
                        String body = "{\"common\":[\"todo-user-id\",\"d022cf79-7c0e-4929-8fb4-2c80b510e4f0\",1695694585279,\"1.0.0.220519\",\"1\",\"设备侦测器\",\"com.coolxer.probe.demo\",\"1.0\",\"android\",\"Google\",\"Pixel XL\",\"Android\",\"9\",\"Wi-Fi\",\"172.16.18.225\",\"0.0.0.0\",-1,-1,\"\",\"\",\"\",\"\",\"\",\"2023-09-26 10:16:28\"]}";

                        for (int i = 0; i < 1; i++) {
                            String resultPost = CTSecureCommunication.post("/risk_index/device_info", "Content-Type:application/json\r\ncode:d022cf79-7c0e-4929-8fb4-2c80b510e4f0-android-1-1.0.0.220519\r\n", body);
                            Log.d("Entrance", "resultPost:" + resultPost);
//                            String resultGet = CTSecureCommunication.get("/1/a?name=yaoqi&age=20&name=lisi" + i, "test:ddd\r\n");
//                            Log.d("Entrance", "resultGet:" + resultGet);

                        }
                    }
                }.start();
                // end
            }
        });
    }
}