package com.coolxer.securecommunication;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.coolxer.securecommunication.identity.AndroidIdentityKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class IdentityStoreInstrumentedTest {
    @Test public void v2IdentityIsStableAndCanSign() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AndroidIdentityKeyStore store = new AndroidIdentityKeyStore(context);
        InstallationIdentity first = store.loadOrCreate("instrumented-v2");
        InstallationIdentity second = store.loadOrCreate("instrumented-v2");
        assertEquals(first.deviceId(), second.deviceId());
        assertTrue(first.publicKeySpki().length > 64);
        assertEquals(64, first.sign("challenge".getBytes(StandardCharsets.UTF_8)).length);
    }
}
