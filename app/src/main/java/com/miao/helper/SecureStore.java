package com.miao.helper;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感字段加密存储：AES-256-GCM。
 * 主密钥由 Android KeyStore 生成并保管（硬件支持时存 TEE/StrongBox），应用层拿不到密钥字节，
 * 其他应用与备份均无法解密；每次加密使用随机 IV，密文含 GCM 认证标签，防篡改。
 * 存储格式：Base64( IV(12B) || 密文+tag )。
 */
public final class SecureStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "miao_master_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;          // GCM 推荐 12 字节 IV
    private static final int TAG_BITS = 128;       // 认证标签 128 位

    private SecureStore() {}

    /** 并发锁：首次生成 Key 时多线程同时进入会重复 generate（AndroidKeyStore 别名冲突抛异常→误降级明文），加锁保证单例 */
    private static final Object KEY_LOCK = new Object();

    private static SecretKey getOrCreateKey() {
        synchronized (KEY_LOCK) {
            return getOrCreateKeyLocked();
        }
    }

    private static SecretKey getOrCreateKeyLocked() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)   // 强制每次随机 IV
                    .build();
            kg.init(spec);
            return kg.generateKey();
        } catch (Exception e) {
            AppLog.e("SecureStore", "生成/读取主密钥失败：" + e);
            return null;
        }
    }

    /** 加密明文，返回 Base64(IV+密文)；失败或入参为空时返回 null/空串 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            AppLog.e("SecureStore", "加密失败：" + e);
            return null;
        }
    }

    /** 解密 encrypt() 产出的密文；任何失败（换机/数据损坏）返回空串，绝不抛出让上层崩溃 */
    public static String decrypt(String blob) {
        if (blob == null || blob.isEmpty()) return "";
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) return "";
            byte[] all = Base64.decode(blob, Base64.NO_WRAP);
            if (all.length <= IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            AppLog.w("SecureStore", "解密失败（可能换机或数据损坏），按空处理：" + e);
            return "";
        }
    }

    /** 生成加密用随机 IV（保留工具方法，当前由 Cipher 自动产出） */
    static byte[] randomIv() {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        return iv;
    }
}
