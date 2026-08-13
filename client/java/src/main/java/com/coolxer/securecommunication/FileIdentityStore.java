package com.coolxer.securecommunication;

import com.coolxer.securecommunication.internal.CryptoSupport;
import com.coolxer.securecommunication.internal.JsonSupport;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owner-only, atomic file identity store for host applications. */
public final class FileIdentityStore implements IdentityStore {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final Path path;

    public FileIdentityStore(Path path) {
        if (path == null || path.getFileName() == null) {
            throw new IllegalArgumentException("identity path is required");
        }
        this.path = path.toAbsolutePath().normalize();
    }

    @Override
    public InstallationIdentity loadOrCreate(String appId) throws SecureError {
        Path directory = path.getParent();
        if (directory == null) throw identityFailure(null);
        Path lockPath = directory.resolve(path.getFileName() + ".lock");
        try {
            createSecureDirectory(directory);
            rejectSymbolicLink(path);
            rejectSymbolicLink(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                securePermissions(lockPath, false);
                return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        ? load(appId) : create(appId);
            }
        } catch (SecureError error) {
            throw error;
        } catch (Exception exception) {
            throw identityFailure(exception);
        }
    }

    private InstallationIdentity load(String appId) throws Exception {
        rejectSymbolicLink(path);
        securePermissions(path, false);
        StoredIdentity stored = JsonSupport.readStrict(Files.readAllBytes(path), StoredIdentity.class);
        if (stored.version() != 1 || !appId.equals(stored.appId())
                || stored.deviceId() == null || stored.deviceId().isBlank()
                || stored.privateKey() == null) {
            throw new IOException("Invalid identity file");
        }
        byte[] encoded = decode(stored.privateKey());
        PrivateKey privateKey = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        CryptoSupport.requireP256(privateKey);
        PublicKey publicKey = CryptoSupport.parseP256Public(decode(stored.publicKey()));
        byte[] challenge = "SC1/identity-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!CryptoSupport.verifyP1363(
                publicKey, challenge, CryptoSupport.signP1363(privateKey, challenge))) {
            throw new IOException("Identity key pair does not match");
        }
        return new FileIdentity(stored.deviceId(), privateKey, publicKey);
    }

    private InstallationIdentity create(String appId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String deviceId = UUID.randomUUID().toString();
        StoredIdentity stored = new StoredIdentity(1, appId, deviceId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(pair.getPublic().getEncoded()));
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            securePermissions(temporary, false);
            byte[] encoded = JsonSupport.write(stored);
            try (FileChannel output = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                output.write(java.nio.ByteBuffer.wrap(encoded));
                output.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic identity replacement is unavailable", exception);
            }
            securePermissions(path, false);
            forceDirectory(path.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new FileIdentity(deviceId, pair.getPrivate(), pair.getPublic());
    }

    private static void createSecureDirectory(Path directory) throws Exception {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymbolicLink(directory);
        } else {
            Files.createDirectories(directory);
        }
        securePermissions(directory, true);
    }

    private static void rejectSymbolicLink(Path value) throws IOException {
        if (Files.isSymbolicLink(value)) throw new IOException("Symbolic links are not allowed");
    }

    private static void securePermissions(Path value, boolean directory) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                value, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> expected = directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            Files.setPosixFilePermissions(value, expected);
            if (!Files.getPosixFilePermissions(value, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
                throw new IOException("Unable to enforce owner-only POSIX permissions");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                value, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Owner-only file permissions are unavailable");
        UserPrincipal owner = Files.getOwner(value, LinkOption.NOFOLLOW_LINKS);
        EnumSet<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(owner).setPermissions(permissions).build()));
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // Some filesystems do not support opening directories. The file itself was forced.
        }
    }

    private static byte[] decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid key encoding");
        return Base64.getUrlDecoder().decode(value);
    }

    private static SecureError identityFailure(Throwable cause) {
        return new SecureError("SC_IDENTITY_FAILED", "Installation identity is unavailable",
                0, null, cause);
    }

    private record StoredIdentity(
            int version, String appId, String deviceId,
            String privateKey, String publicKey) { }

    private record FileIdentity(String deviceId, PrivateKey privateKey, PublicKey publicKey)
            implements InstallationIdentity {
        @Override public byte[] publicKeySpki() { return publicKey.getEncoded().clone(); }
        @Override public byte[] sign(byte[] data) throws SecureError {
            return CryptoSupport.signP1363(privateKey, data);
        }
    }
}
