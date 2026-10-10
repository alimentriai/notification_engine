package com.a1.notify.template;

import com.a1.notify.domain.Channel;
import com.a1.notify.domain.TemplateEntity;
import com.a1.notify.repository.TemplateRepository;
import com.a1.notify.web.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/** Versioned, per-project templates with {{variable}} substitution. HTML bodies escape every value. */
@Service
public class TemplateService {

    static final String DEFAULT_LOCALE = "en";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    private final TemplateRepository templates;

    public TemplateService(TemplateRepository templates) {
        this.templates = templates;
    }

    public record Rendered(String subject, String html, String text, String dltTemplateId) {
    }

    /** Latest active version for the locale, falling back to English. */
    @Transactional(readOnly = true)
    public TemplateEntity resolve(Long projectId, String code, Channel channel, String locale) {
        Optional<TemplateEntity> found = templates
                .findFirstByProjectIdAndCodeAndChannelAndLocaleAndActiveTrueOrderByVersionDesc(projectId, code, channel, locale);
        if (found.isEmpty() && !DEFAULT_LOCALE.equals(locale)) {
            found = templates.findFirstByProjectIdAndCodeAndChannelAndLocaleAndActiveTrueOrderByVersionDesc(
                    projectId, code, channel, DEFAULT_LOCALE);
        }
        return found.orElseThrow(() -> ApiException.badRequest("TEMPLATE_NOT_FOUND",
                "No active template '" + code + "' for channel " + channel));
    }

    /** Looks up the exact pinned version used when the notification was accepted. */
    @Transactional(readOnly = true)
    public Optional<TemplateEntity> pinned(Long projectId, String code, Channel channel, String locale, int version) {
        return templates.findByProjectIdAndCodeAndChannelAndLocaleAndVersion(projectId, code, channel, locale, version);
    }

    public Rendered render(TemplateEntity template, Map<String, String> variables) {
        return new Rendered(
                template.getSubject() == null ? null : substitute(template.getSubject(), variables, false),
                template.getBodyHtml() == null ? null : substitute(template.getBodyHtml(), variables, true),
                template.getBodyText() == null ? null : substitute(template.getBodyText(), variables, false),
                template.getDltTemplateId());
    }

    public Rendered renderRaw(String subject, String html, String text, Map<String, String> variables) {
        return new Rendered(
                subject == null ? null : substitute(subject, variables, false),
                html == null ? null : substitute(html, variables, true),
                text == null ? null : substitute(text, variables, false),
                null);
    }

    static String substitute(String content, Map<String, String> variables, boolean escapeHtml) {
        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw ApiException.badRequest("TEMPLATE_VARIABLE_MISSING", "Missing template variable: " + name);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(escapeHtml ? HtmlUtils.htmlEscape(value) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Transactional
    public TemplateEntity create(Long projectId, String code, Channel channel, String locale, String subject,
                                 String bodyHtml, String bodyText, String dltTemplateId) {
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank() || (bodyHtml == null && bodyText == null))) {
            throw ApiException.badRequest("INVALID_TEMPLATE", "Email templates need a subject and an html or text body");
        }
        if ((channel == Channel.SMS || channel == Channel.PUSH || channel == Channel.IN_APP)
                && (bodyText == null || bodyText.isBlank())) {
            throw ApiException.badRequest("INVALID_TEMPLATE", channel + " templates need bodyText");
        }
        String loc = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
        int next = templates.findFirstByProjectIdAndCodeAndChannelAndLocaleOrderByVersionDesc(projectId, code, channel, loc)
                .map(t -> t.getVersion() + 1).orElse(1);
        TemplateEntity template = new TemplateEntity();
        template.setProjectId(projectId);
        template.setCode(code);
        template.setChannel(channel);
        template.setLocale(loc);
        template.setVersion(next);
        template.setSubject(subject);
        template.setBodyHtml(bodyHtml);
        template.setBodyText(bodyText);
        template.setDltTemplateId(dltTemplateId);
        template.setActive(true);
        template.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return templates.save(template);
    }

    @Transactional(readOnly = true)
    public List<TemplateEntity> list(Long projectId) {
        return templates.findByProjectIdOrderByCodeAscVersionDesc(projectId);
    }

    @Transactional
    public void setActive(Long projectId, Long templateId, boolean active) {
        TemplateEntity template = templates.findById(templateId)
                .filter(t -> t.getProjectId().equals(projectId))
                .orElseThrow(() -> ApiException.notFound("Template not found"));
        template.setActive(active);
    }
}
