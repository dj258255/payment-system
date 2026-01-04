package com.beomsu.pay.shared.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 필드 단위 암호화 — <b>Envelope Encryption(DEK/KEK)</b>.
 *
 * <p>{@link AesGcmFieldCipher}(단일 키)를 실서비스/금융권 방식으로 확장한다. 데이터는 매번 새
 * <b>DEK(Data Encryption Key)</b>로 AES-256-GCM 암호화하고, 그 DEK를 <b>KEK(마스터키, KMS 보관)</b>로
 * 감싸(wrap) 암호문과 함께 저장한다. 이렇게 하면
 * <ol>
 *   <li>키 로테이션 시 <b>모든 데이터를 재암호화하지 않고</b> 작은 DEK만 새 KEK로 재-wrap하면 된다
 *       ({@link #rewrapToCurrent}).</li>
 *   <li>마스터키(KEK)가 KMS 밖으로 나오지 않는다({@link MasterKeyProvider}가 실 KMS로 교체 가능).</li>
 * </ol>
 *
 * <h2>암호문 포맷</h2>
 * <pre>{@code
 *   env:{version}:{base64(wrapBlob)}:{base64(dataBlob)}
 * }</pre>
 * <ul>
 *   <li>{@code version} — DEK를 감싼 KEK의 버전(복호화 때 어느 KEK인지 안다).</li>
 *   <li>{@code dataBlob} — 데이터 암호문: {@code iv(12) + ct + tag}, DEK로 GCM.</li>
 *   <li>{@code wrapBlob} — 감싼 DEK: {@code iv(12) + wrappedDek + tag}, KEK로 GCM.</li>
 * </ul>
 * 버전을 prefix로 실으므로 로테이션 후에도 옛 암호문을 옛 KEK로 복호화할 수 있다.
 *
 * <p><b>주의(스키마):</b> envelope 암호문은 wrap된 DEK를 함께 실어 단일 키 방식보다 길다
 * (base64 wrapBlob 60여 바이트 + 버전 prefix). 민감 컬럼 길이를 넉넉히 잡아야 한다.
 *
 * <h2>빈 선택</h2>
 * {@code app.crypto.mode=envelope}(기본, {@code matchIfMissing=true})일 때만 생성되며 {@code @Primary}라
 * {@link FieldCipher} 주입 시 우선한다. {@link AesGcmFieldCipher} 빈은 그대로 남지만 주입되지 않는다.
 * {@code mode=simple}로 두면 이 빈이 만들어지지 않아 AesGcm이 주입된다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.crypto.mode", havingValue = "envelope", matchIfMissing = true)
public class EnvelopeFieldCipher implements FieldCipher {

    private static final String PREFIX = "env:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;       // GCM 권장 96비트
    private static final int TAG_LENGTH_BITS = 128;
    private static final int DEK_BITS = 256;        // AES-256 DEK

    private final MasterKeyProvider masterKeys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeFieldCipher(MasterKeyProvider masterKeys) {
        this.masterKeys = masterKeys;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            // 1) 무작위 DEK(AES-256).
            SecretKey dek = generateDek();
            // 2) 데이터 암호화: DEK + 무작위 iv → iv+ct+tag.
            byte[] dataBlob = gcmEncrypt(dek, plaintext.getBytes(StandardCharsets.UTF_8));
            // 3) DEK wrap: currentKEK + 무작위 iv → iv+wrappedDek+tag.
            String version = masterKeys.currentVersion();
            byte[] wrapBlob = gcmEncrypt(masterKeys.keyFor(version), dek.getEncoded());
            // 4) 버전을 실은 포맷.
            return format(version, wrapBlob, dataBlob);
        } catch (Exception e) {
            throw new IllegalStateException("필드 암호화 실패", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        Parsed p = parse(ciphertext);
        try {
            // 버전으로 KEK를 찾아 DEK를 복원(unwrap) → 데이터 복호화.
            SecretKey kek = masterKeys.keyFor(p.version);
            byte[] dekBytes = gcmDecrypt(kek, p.wrapBlob);
            SecretKey dek = new SecretKeySpec(dekBytes, "AES");
            byte[] pt = gcmDecrypt(dek, p.dataBlob);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 알 수 없는 버전 등 — 그대로 전파(테스트가 구분)
            throw e;
        } catch (Exception e) {
            // GCM 인증 실패(변조) 포함
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    /**
     * 키 로테이션 — 데이터 재암호화 없이 DEK만 current KEK로 재-wrap한다.
     *
     * <p>암호문의 버전이 이미 current면 그대로 반환한다. 다르면 옛 KEK로 DEK를 unwrap한 뒤
     * <b>current KEK로 다시 wrap</b>하고, {@code dataBlob}(실제 데이터 암호문)은 <b>그대로 둔 채</b>
     * 새 {@code env:{current}:{newWrap}:{dataBlob}}를 반환한다. 이것이 envelope의 핵심 이점 —
     * 수백만 행을 재암호화하는 대신 각 행의 작은 DEK만 재-wrap하면 된다.
     */
    public String rewrapToCurrent(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        Parsed p = parse(ciphertext);
        String current = masterKeys.currentVersion();
        if (p.version.equals(current)) {
            return ciphertext; // 이미 current — 할 일 없음.
        }
        try {
            // 옛 KEK로 DEK unwrap → current KEK로 다시 wrap. dataBlob은 불변.
            SecretKey oldKek = masterKeys.keyFor(p.version);
            byte[] dekBytes = gcmDecrypt(oldKek, p.wrapBlob);
            byte[] newWrapBlob = gcmEncrypt(masterKeys.keyFor(current), dekBytes);
            return format(current, newWrapBlob, p.dataBlob);
        } catch (Exception e) {
            throw new IllegalStateException("재-wrap 실패", e);
        }
    }

    // --- 내부 헬퍼 ---

    private SecretKey generateDek() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(DEK_BITS, random);
        return kg.generateKey();
    }

    /** GCM 암호화: 무작위 iv를 앞에 붙여 {@code iv(12) + ct + tag} 반환. */
    private byte[] gcmEncrypt(SecretKey key, byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    /** GCM 복호화: {@code iv(12) + ct + tag} 형식을 받아 평문 반환. */
    private byte[] gcmDecrypt(SecretKey key, byte[] blob) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(blob, 0, iv, 0, IV_LENGTH);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
    }

    private static String format(String version, byte[] wrapBlob, byte[] dataBlob) {
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + version + ":" + b64.encodeToString(wrapBlob) + ":" + b64.encodeToString(dataBlob);
    }

    private static Parsed parse(String ciphertext) {
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("envelope 형식이 아닙니다(env: 프리픽스 없음).");
        }
        // "env:" 이후를 version:wrap:data 3조각으로.
        String body = ciphertext.substring(PREFIX.length());
        String[] parts = body.split(":", 3);
        if (parts.length != 3 || parts[0].isBlank()) {
            throw new IllegalStateException("envelope 형식이 손상됐습니다.");
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            return new Parsed(parts[0], b64.decode(parts[1]), b64.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("envelope base64 디코드 실패(변조 의심)", e);
        }
    }

    private record Parsed(String version, byte[] wrapBlob, byte[] dataBlob) {
    }
}
