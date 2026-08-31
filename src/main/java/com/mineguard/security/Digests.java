package com.mineguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Digests {
    private Digests() {}
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("无法生成摘要"); }
    }
    public static String canonical(ObjectMapper mapper, Object value) {
        try { return sha256(mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value)); }
        catch (Exception ex) { throw new IllegalArgumentException("无法生成操作参数摘要"); }
    }
}
