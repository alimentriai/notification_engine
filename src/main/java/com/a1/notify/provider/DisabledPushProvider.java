package com.a1.notify.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Active while notify.providers.push.enabled=false (default). To enable push, set the flag to true and
 * register a PushProvider bean (for example an FCM adapter).
 */
@Component
@ConditionalOnProperty(name = "notify.providers.push.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledPushProvider implements PushProvider {

    @Override
    public String name() {
        return "push-disabled";
    }

    @Override
    public ProviderResult send(PushMessage message) {
        return ProviderResult.permanentFailure("PUSH_DISABLED");
    }
}
