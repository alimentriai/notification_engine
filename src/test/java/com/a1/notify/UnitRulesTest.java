package com.a1.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.core.RecipientValidator;
import com.a1.notify.core.RetryPolicy;
import com.a1.notify.domain.Category;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.template.TemplateService;
import com.a1.notify.web.ApiException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitRulesTest {

    @Test
    void backoffGrowsAndIsBounded() {
        NotifyProperties props = new NotifyProperties();
        props.getRetry().setBaseDelaySeconds(10);
        props.getRetry().setMaxDelaySeconds(100);
        props.getRetry().setJitter(0);
        props.getRetry().setMaxAttempts(5);
        RetryPolicy policy = new RetryPolicy(props);
        assertThat(policy.delaySeconds(1)).isEqualTo(10);
        assertThat(policy.delaySeconds(2)).isEqualTo(20);
        assertThat(policy.delaySeconds(3)).isEqualTo(40);
        assertThat(policy.delaySeconds(10)).isEqualTo(100);
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
    }

    @Test
    void jitterStaysWithinBounds() {
        NotifyProperties props = new NotifyProperties();
        props.getRetry().setBaseDelaySeconds(100);
        props.getRetry().setMaxDelaySeconds(120);
        props.getRetry().setJitter(0.2);
        RetryPolicy policy = new RetryPolicy(props);
        for (int i = 0; i < 200; i++) {
            assertThat(policy.delaySeconds(1)).isBetween(80L, 120L);
        }
    }

    @Test
    void statusLifecycle() {
        assertThat(NotificationStatus.PENDING.canTransitionTo(NotificationStatus.PROCESSING)).isTrue();
        assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.SENT)).isTrue();
        assertThat(NotificationStatus.SENT.canTransitionTo(NotificationStatus.PENDING)).isFalse();
        assertThat(NotificationStatus.CANCELLED.canTransitionTo(NotificationStatus.PENDING)).isFalse();
        assertThat(NotificationStatus.SENT.isTerminal()).isTrue();
        assertThat(NotificationStatus.PROCESSING.isTerminal()).isFalse();
    }

    @Test
    void categoryConsentRules() {
        assertThat(Category.OTP.isMandatory()).isTrue();
        assertThat(Category.SYSTEM.isMandatory()).isTrue();
        assertThat(Category.MARKETING.isOptIn()).isTrue();
        assertThat(Category.INFO.isMandatory()).isFalse();
    }

    @Test
    void phoneAndEmailValidation() {
        assertThat(RecipientValidator.phone("+919876543210")).isEqualTo("+919876543210");
        assertThat(RecipientValidator.phone("+14155550123")).isEqualTo("+14155550123");
        assertThatThrownBy(() -> RecipientValidator.phone("9876543210")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RecipientValidator.phone("+911234567890")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RecipientValidator.phone("+91987654")).isInstanceOf(ApiException.class);
        assertThat(RecipientValidator.email(" a@b.co ")).isEqualTo("a@b.co");
        assertThatThrownBy(() -> RecipientValidator.email("not-an-email")).isInstanceOf(ApiException.class);
    }

    @Test
    void templateSubstitutionEscapesHtmlOnly() throws Exception {
        Method substitute = TemplateService.class.getDeclaredMethod("substitute", String.class, Map.class, boolean.class);
        substitute.setAccessible(true);
        Map<String, String> vars = Map.of("name", "<b>Ann & Bob</b>");
        assertThat(substitute.invoke(null, "Hi {{ name }}", vars, true)).isEqualTo("Hi &lt;b&gt;Ann &amp; Bob&lt;/b&gt;");
        assertThat(substitute.invoke(null, "Hi {{name}}", vars, false)).isEqualTo("Hi <b>Ann & Bob</b>");
        assertThatThrownBy(() -> {
            try {
                substitute.invoke(null, "Hi {{missing}}", vars, false);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause();
            }
        }).isInstanceOf(ApiException.class).hasMessageContaining("missing");
    }
}
