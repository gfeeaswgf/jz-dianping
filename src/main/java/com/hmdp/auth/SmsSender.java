package com.hmdp.auth;

/** 短信通道端口，本项目仅提供无付费调用的模拟实现。 */
public interface SmsSender {
    /**
     * 提交一次发送，不在实现内自动重发，不记录参数或通道原始响应。
     * @param phone 已校验手机号
     * @param code 六位数字验证码
     * @param issueId 签发所有权标识
     * @return 明确接受、明确失败或结果不确定
     */
    SmsSendOutcome send(String phone,String code,String issueId);
}
