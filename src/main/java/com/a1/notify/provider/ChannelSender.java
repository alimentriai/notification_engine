package com.a1.notify.provider;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.domain.ProjectEntity;
import com.a1.notify.template.TemplateService.Rendered;
import org.springframework.stereotype.Component;

/** Resolves project-level sender settings and calls the active provider. Shared by queue dispatch and OTP. */
@Component
public class ChannelSender {

    private final EmailProvider email;
    private final SmsProvider sms;
    private final PushProvider push;
    private final NotifyProperties props;

    public ChannelSender(EmailProvider email, SmsProvider sms, PushProvider push, NotifyProperties props) {
        this.email = email;
        this.sms = sms;
        this.push = push;
        this.props = props;
    }

    public String emailProviderName() {
        return email.name();
    }

    public String smsProviderName() {
        return sms.name();
    }

    public String pushProviderName() {
        return push.name();
    }

    public ProviderResult sendEmail(ProjectEntity project, String to, Rendered rendered, String correlationId) {
        String from = notBlank(project.getDefaultSenderEmail()) ? project.getDefaultSenderEmail() : props.getEmail().getDefaultSender();
        String configSet = notBlank(project.getSesConfigSet()) ? project.getSesConfigSet() : props.getEmail().getConfigurationSet();
        return email.send(new EmailProvider.EmailMessage(from, to, rendered.subject(), rendered.html(), rendered.text(),
                configSet, correlationId));
    }

    public ProviderResult sendSms(ProjectEntity project, String phone, Rendered rendered, String smsType, String correlationId) {
        String senderId = notBlank(project.getSmsSenderId()) ? project.getSmsSenderId() : props.getSms().getDefaultSenderId();
        return sms.send(new SmsProvider.SmsMessage(phone, rendered.text(),
                smsType == null ? props.getSms().getDefaultType() : smsType, senderId,
                project.getSmsEntityId(), rendered.dltTemplateId(), correlationId));
    }

    public ProviderResult sendPush(String platform, String token, String title, String body, String correlationId) {
        return push.send(new PushProvider.PushMessage(platform, token, title, body, correlationId));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
