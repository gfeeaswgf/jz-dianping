package com.hmdp.exception;

/** 参数无效时的统一安全异常，不回显用户输入。 */
public class ProtectionInvalidRequestException extends RuntimeException {
    /** 创建不包含请求原文的参数异常。 */
    public ProtectionInvalidRequestException() { super("请求参数不合法"); }
}
