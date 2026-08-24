package com.coolxer.securecommunication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileIdentityStoreTest {
    @TempDir Path temporary;

    @Test
    void createsAndReusesOwnerOnlyIdentity() throws Exception {
        Path identityPath = temporary.resolve("private").resolve("identity.json");
        FileIdentityStore store = new FileIdentityStore(identityPath);
        InstallationIdentity first = store.loadOrCreate("host-app");
        InstallationIdentity second = store.loadOrCreate("host-app");
        assertEquals(first.deviceId(), second.deviceId());
        assertArrayEquals(first.publicKeySpki(), second.publicKeySpki());
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(identityPath));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(identityPath.getParent()));
        byte[] signature = first.sign("proof".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, signature.length);
        assertEquals("SC_IDENTITY_FAILED", assertThrows(SecureError.class,
                () -> store.loadOrCreate("different-app")).getCode());
    }

    @Test
    void rejectsCorruptIdentity() throws Exception {
        Path identityPath = temporary.resolve("corrupt").resolve("identity.json");
        Files.createDirectories(identityPath.getParent());
        Files.writeString(identityPath, "{\"version\":1}");
        SecureError error = assertThrows(SecureError.class,
                () -> new FileIdentityStore(identityPath).loadOrCreate("host-app"));
        assertEquals("SC_IDENTITY_FAILED", error.getCode());
    }

    @Test
    void requestAndResponseDefensivelyCopyValues() {
        byte[] body = {1, 2};
        SecureRequest request = SecureRequest.builder().method("post")
                .logicalPath("/events").contentType("Application/JSON; charset=utf-8")
                .protectedHeaders(java.util.Map.of("X-Code", "value"))
                .body(body).build();
        body[0] = 9;
        assertEquals("POST", request.getMethod());
        assertEquals("application/json", request.getContentType());
        assertTrue(request.getProtectedHeaders().containsKey("x-code"));
        assertNotEquals(9, request.getBody()[0]);
        assertThrows(IllegalArgumentException.class, () -> SecureRequest.builder()
                .logicalPath("/events").protectedHeaders(java.util.Map.of("bad", "x\nvalue"))
                .build());
    }
}
