package com.hmdp.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 无外部调用、无敏感日志的短信模拟器，不把模拟成功宣传为实际送达。 */
@Component
@ConditionalOnProperty(prefix="protection",name="sms-mode",havingValue="mock",matchIfMissing=true)
public class MockSmsSender implements SmsSender {
    /** {@inheritDoc} 模拟通道仅返回接受，不输出任何请求内容。 */
    @Override public SmsSendOutcome send(String phone,String code,String issueId) { return SmsSendOutcome.ACCEPTED; }
}
