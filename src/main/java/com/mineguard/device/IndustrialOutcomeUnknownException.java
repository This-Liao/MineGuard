package com.mineguard.device;

public class IndustrialOutcomeUnknownException extends IllegalStateException {
    public IndustrialOutcomeUnknownException() { super("工业写请求已发出，但无法确认结果"); }
}
