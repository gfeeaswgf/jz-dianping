package com.hmdp.exception;

/** 保护组件不可用；不附原始异常链，避免 Redis/SQL 异常包含凭证或手机号。 */
public class ProtectionUnavailableException extends RuntimeException {
    private final String operationCode;
    /**
     * @param operationCode 固定内部操作码，非法内容自动替换，不接受业务参数
     */
    public ProtectionUnavailableException(String operationCode) {
        super("请求暂不可用，请稍后重试");
        this.operationCode = operationCode != null && operationCode.matches("[A-Z_]{1,64}")
                ? operationCode : "PROTECTION_FAILURE";
    }
    /**
     * @return 可安全记录的固定操作码
     */
    public String getOperationCode() { return operationCode; }
}
