package com.a1.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.a1.notify.core.NotificationDispatcher;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.domain.NotificationStatus;
import com.a1.notify.provider.EmailProvider;
import com.a1.notify.provider.EmailProvider.EmailMessage;
import com.a1.notify.provider.ProviderResult;
import com.a1.notify.provider.SmsProvider;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.repository.OtpChallengeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    private static final String ADMIN = "test-admin-key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired NotificationRepository notifications;
    @Autowired OtpChallengeRepository otpChallenges;
    @Autowired NotificationDispatcher dispatcher;
    @MockitoBean EmailProvider email;
    @MockitoBean SmsProvider sms;

    private String addr;
    private String phone;

    private List<EmailMessage> emailsTo(String address) {
        return Mockito.mockingDetails(email).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (EmailMessage) i.getArgument(0))
                .filter(m -> m != null && address.equals(m.to())).toList();
    }

    private List<SmsProvider.SmsMessage> smsTo(String number) {
        return Mockito.mockingDetails(sms).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (SmsProvider.SmsMessage) i.getArgument(0))
                .filter(m -> m != null && number.equals(m.phone())).toList();
    }

    private EmailMessage forAddr(String address) {
        return argThat(m -> m != null && address.equals(m.to()));
    }

    @BeforeEach
    void stubProviders() {
        String unique = UUID.randomUUID().toString().substring(0, 12);
        addr = "ann-" + unique + "@example.com";
        phone = "+9198" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L));
        when(email.name()).thenReturn("mock-email");
        when(sms.name()).thenReturn("mock-sms");
        when(email.send(any())).thenReturn(ProviderResult.sent("e-1"));
        when(sms.send(any())).thenReturn(ProviderResult.sent("s-1"));
    }

    // ---------- helpers ----------

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder b, String key, Object body) throws Exception {
        b.header("X-Api-Key", key);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return b;
    }

    private String newProject(Integer ratePerMin) throws Exception {
        String code = "p-" + UUID.randomUUID().toString().substring(0, 8);
        var body = new java.util.HashMap<String, Object>();
        body.put("code", code);
        body.put("name", "Project " + code);
        body.put("defaultSenderEmail", "no-reply@" + code + ".test");
        if (ratePerMin != null) {
            body.put("rateLimitPerMin", ratePerMin);
        }
        mvc.perform(withKey(post("/admin/projects"), ADMIN, body)).andExpect(status().isCreated());
        return code;
    }

    private String newKey(String project, String... scopes) throws Exception {
        MvcResult r = mvc.perform(withKey(post("/admin/projects/" + project + "/keys"), ADMIN,
                java.util.Map.of("label", "test", "scopes", List.of(scopes)))).andExpect(status().isCreated()).andReturn();
        return json(r).get("key").asText();
    }

    private String fullKey(String project) throws Exception {
        return newKey(project, "SEND", "READ", "RETRY", "TEMPLATES", "PREFERENCES", "OTP", "INBOX");
    }

    private void template(String key, String code, String channel, String subject, String html, String text) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("code", code);
        body.put("channel", channel);
        body.put("subject", subject);
        body.put("bodyHtml", html);
        body.put("bodyText", text);
        mvc.perform(withKey(post("/templates"), key, body)).andExpect(status().isCreated());
    }

    private java.util.Map<String, Object> request(String category, String templateCode, List<String> channels, String idem) {
        var body = new java.util.HashMap<String, Object>();
        body.put("type", "TEST_EVENT");
        body.put("category", category);
        body.put("channels", channels);
        body.put("templateCode", templateCode);
        body.put("recipient", java.util.Map.of("ref", "user-1", "email", addr, "phone", phone));
        body.put("variables", java.util.Map.of("name", "Ann"));
        if (idem != null) {
            body.put("idempotencyKey", idem);
        }
        return body;
    }

    private String send(String key, java.util.Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(withKey(post("/notifications"), key, body)).andExpect(status().isAccepted()).andReturn();
        return json(r).get("notifications").get(0).get("id").asText();
    }

    private JsonNode awaitStatus(String key, String id, String expected) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 60; i++) {
            last = json(mvc.perform(withKey(get("/notifications/" + id), key, null)).andExpect(status().isOk()).andReturn());
            if (expected.equals(last.get("status").asText())) {
                return last;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Expected " + expected + " but was " + last);
    }

    private void emailTemplate(String key) throws Exception {
        template(key, "welcome", "EMAIL", "Hi {{name}}", "<p>Hello {{name}}</p>", "Hello {{name}}");
    }

    // ---------- security ----------

    @Test
    void requiresApiKeyAndSeparatesAdminFromProjects() throws Exception {
        mvc.perform(get("/notifications").header("X-Correlation-Id", "corr-401")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("corr-401"));
        mvc.perform(get("/notifications").header("X-Api-Key", "bogus")).andExpect(status().isUnauthorized());
        String project = newProject(null);
        String key = fullKey(project);
        mvc.perform(get("/admin/projects").header("X-Api-Key", key)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/projects").header("X-Api-Key", ADMIN)).andExpect(status().isOk());
        mvc.perform(get("/notifications").header("X-Api-Key", ADMIN)).andExpect(status().isForbidden());
    }

    @Test
    void scopesAreEnforced() throws Exception {
        String project = newProject(null);
        String readOnly = newKey(project, "READ");
        mvc.perform(withKey(post("/notifications"), readOnly, request("INFO", "x", List.of("IN_APP"), null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/notifications").header("X-Api-Key", readOnly)).andExpect(status().isOk());
    }

    @Test
    void keysAreStoredHashedAndRevocable() throws Exception {
        String project = newProject(null);
        MvcResult r = mvc.perform(withKey(post("/admin/projects/" + project + "/keys"), ADMIN,
                java.util.Map.of("label", "k", "scopes", List.of("READ")))).andReturn();
        String raw = json(r).get("key").asText();
        long id = json(r).get("id").asLong();
        assertThat(raw).startsWith("ntf_");
        mvc.perform(get("/notifications").header("X-Api-Key", raw)).andExpect(status().isOk());
        MvcResult listed = mvc.perform(get("/admin/projects/" + project + "/keys").header("X-Api-Key", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].prefix").value(raw.substring(0, 8)))
                .andExpect(jsonPath("$[0].active").value(true)).andReturn();
        assertThat(listed.getResponse().getContentAsString()).doesNotContain(raw).doesNotContain("keyHash");
        mvc.perform(get("/admin/projects/" + project + "/keys").header("X-Api-Key", raw)).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/projects/" + project + "/keys/" + id).header("X-Api-Key", ADMIN)).andExpect(status().isNoContent());
        mvc.perform(get("/notifications").header("X-Api-Key", raw)).andExpect(status().isUnauthorized());
    }

    // ---------- delivery ----------

    @Test
    void emailIsPersistedDispatchedAndNeverExposesContactData() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        String id = send(key, request("INFO", "welcome", List.of("EMAIL"), null));
        JsonNode done = awaitStatus(key, id, "SENT");
        assertThat(done.get("attempts").asInt()).isEqualTo(1);
        assertThat(done.toString()).doesNotContain(addr).doesNotContain("recipientEmail");
        assertThat(emailsTo(addr)).hasSize(1);
        EmailMessage sent = emailsTo(addr).get(0);
        assertThat(sent.subject()).isEqualTo("Hi Ann");
        assertThat(sent.from()).endsWith(".test");
        assertThat(sent.html()).isEqualTo("<p>Hello Ann</p>");
        assertThat(sent.text()).isEqualTo("Hello Ann");
    }

    @Test
    void smsGoesThroughSmsProvider() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        template(key, "sms-hello", "SMS", null, null, "Hello {{name}}");
        String id = send(key, request("INFO", "sms-hello", List.of("SMS"), null));
        awaitStatus(key, id, "SENT");
        assertThat(smsTo(phone)).hasSize(1);
        assertThat(emailsTo(addr)).isEmpty();
    }

    @Test
    void multiChannelCreatesOneRowPerChannelAndInAppIsImmediatelyReadable() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        template(key, "welcome", "IN_APP", "Welcome {{name}}", null, "Hello {{name}}");
        MvcResult r = mvc.perform(withKey(post("/notifications"), key, request("INFO", "welcome", List.of("EMAIL", "IN_APP"), null)))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(json(r).get("notifications")).hasSize(2);
        mvc.perform(get("/inbox").param("recipientRef", "user-1").header("X-Api-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Welcome Ann"))
                .andExpect(jsonPath("$.data[0].status").value("DELIVERED"));
    }

    @Test
    void inboxReadMarksOnlyOwnItem() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        template(key, "n", "IN_APP", "T", null, "B {{name}}");
        String id = send(key, request("INFO", "n", List.of("IN_APP"), null));
        mvc.perform(withKey(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/inbox/" + id + "/read")
                .param("recipientRef", "someone-else"), key, null)).andExpect(status().isNotFound());
        mvc.perform(withKey(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/inbox/" + id + "/read")
                .param("recipientRef", "user-1"), key, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").isNotEmpty());
    }

    // ---------- validation ----------

    @Test
    void rejectsInvalidInputBeforePersisting() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        var badEmail = request("INFO", "welcome", List.of("EMAIL"), null);
        badEmail.put("recipient", java.util.Map.of("ref", "u", "email", "nope"));
        mvc.perform(withKey(post("/notifications"), key, badEmail)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));

        var missingVar = request("INFO", "welcome", List.of("EMAIL"), null);
        missingVar.put("variables", java.util.Map.of());
        mvc.perform(withKey(post("/notifications"), key, missingVar)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VARIABLE_MISSING"));

        mvc.perform(withKey(post("/notifications"), key, request("INFO", "no-such-template", List.of("EMAIL"), null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));

        mvc.perform(withKey(post("/notifications"), key, request("OTP", "welcome", List.of("EMAIL"), null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("USE_OTP_API"));

        var badPhone = request("INFO", "welcome", List.of("SMS"), null);
        badPhone.put("recipient", java.util.Map.of("ref", "u", "phone", "9876543210"));
        mvc.perform(withKey(post("/notifications"), key, badPhone)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        assertThat(emailsTo(addr)).isEmpty();
    }

    // ---------- idempotency ----------

    @Test
    void sameIdempotencyKeyReturnsOriginalAndSendsOnce() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        String first = send(key, request("INFO", "welcome", List.of("EMAIL"), "order-42"));
        String second = send(key, request("INFO", "welcome", List.of("EMAIL"), "order-42"));
        assertThat(second).isEqualTo(first);
        awaitStatus(key, first, "SENT");
        Thread.sleep(800);
        assertThat(emailsTo(addr)).hasSize(1);
    }

    // ---------- retries ----------

    @Test
    void transientFailureIsRetriedWithBackoffThenSucceeds() throws Exception {
        when(email.send(forAddr(addr))).thenReturn(ProviderResult.transientFailure("Throttled"), ProviderResult.sent("e-2"));
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        String id = send(key, request("INFO", "welcome", List.of("EMAIL"), null));
        JsonNode done = awaitStatus(key, id, "SENT");
        assertThat(done.get("attempts").asInt()).isEqualTo(2);
        assertThat(emailsTo(addr)).hasSize(2);
    }

    @Test
    void permanentFailureIsNotRetried() throws Exception {
        when(email.send(forAddr(addr))).thenReturn(ProviderResult.permanentFailure("MessageRejected"));
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        String id = send(key, request("INFO", "welcome", List.of("EMAIL"), null));
        JsonNode failed = awaitStatus(key, id, "FAILED");
        assertThat(failed.get("retryable").asBoolean()).isFalse();
        assertThat(failed.get("lastErrorCode").asText()).isEqualTo("MessageRejected");
        Thread.sleep(1500);
        assertThat(emailsTo(addr)).hasSize(1);
        mvc.perform(withKey(post("/notifications/" + id + "/retry"), key, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_RETRYABLE"));
    }

    @Test
    void exhaustedTransientFailureCanBeRetriedManually() throws Exception {
        when(email.send(forAddr(addr))).thenReturn(ProviderResult.transientFailure("Throttled"));
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        String id = send(key, request("INFO", "welcome", List.of("EMAIL"), null));
        JsonNode failed = awaitStatus(key, id, "FAILED");
        assertThat(failed.get("retryable").asBoolean()).isTrue();
        assertThat(failed.get("attempts").asInt()).isEqualTo(2);

        when(email.send(forAddr(addr))).thenReturn(ProviderResult.sent("e-3"));
        mvc.perform(withKey(post("/notifications/" + id + "/retry"), key, null)).andExpect(status().isAccepted());
        awaitStatus(key, id, "SENT");
    }

    // ---------- concurrency / duplicates ----------

    @Test
    void duplicateQueueDeliveriesDoNotSendTwice() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        emailTemplate(key);
        var future = request("INFO", "welcome", List.of("EMAIL"), null);
        future.put("scheduledAt", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString());
        String id = send(key, future);

        NotificationEntity n = notifications.findById(UUID.fromString(id)).orElseThrow();
        n.setNextAttemptAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        notifications.save(n);

        int workers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                dispatcher.dispatch(UUID.fromString(id));
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();
        awaitStatus(key, id, "SENT");
        Thread.sleep(500);
        assertThat(emailsTo(addr)).hasSize(1);
    }

    // ---------- preferences ----------

    @Test
    void marketingNeedsConsentAndMandatoryCannotBeDisabled() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        template(key, "promo", "IN_APP", "Promo", null, "Sale for {{name}}");
        String blocked = send(key, request("MARKETING", "promo", List.of("IN_APP"), null));
        JsonNode cancelled = json(mvc.perform(withKey(get("/notifications/" + blocked), key, null)).andReturn());
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("lastErrorCode").asText()).isEqualTo("PREFERENCE_OPT_OUT");

        mvc.perform(withKey(put("/notification-preferences"), key, java.util.Map.of("userRef", "user-1",
                "preferences", List.of(java.util.Map.of("channel", "IN_APP", "category", "MARKETING", "enabled", true)))))
                .andExpect(status().isOk());
        String allowed = send(key, request("MARKETING", "promo", List.of("IN_APP"), null));
        assertThat(json(mvc.perform(withKey(get("/notifications/" + allowed), key, null)).andReturn()).get("status").asText())
                .isEqualTo("DELIVERED");

        mvc.perform(withKey(put("/notification-preferences"), key, java.util.Map.of("userRef", "user-1",
                "preferences", List.of(java.util.Map.of("channel", "EMAIL", "category", "SYSTEM", "enabled", false)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MANDATORY_CATEGORY"));
    }

    @Test
    void optedOutUserSkipsNonEssentialButNotEssential() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        template(key, "n", "IN_APP", "T", null, "B {{name}}");
        mvc.perform(withKey(put("/notification-preferences"), key, java.util.Map.of("userRef", "user-1",
                "preferences", List.of(java.util.Map.of("channel", "IN_APP", "category", "REMINDER", "enabled", false)))))
                .andExpect(status().isOk());
        String reminder = send(key, request("REMINDER", "n", List.of("IN_APP"), null));
        assertThat(json(mvc.perform(withKey(get("/notifications/" + reminder), key, null)).andReturn()).get("status").asText())
                .isEqualTo("CANCELLED");
        String system = send(key, request("SYSTEM", "n", List.of("IN_APP"), null));
        assertThat(json(mvc.perform(withKey(get("/notifications/" + system), key, null)).andReturn()).get("status").asText())
                .isEqualTo("DELIVERED");
    }

    // ---------- multi-project isolation ----------

    @Test
    void projectsCannotSeeEachOthersData() throws Exception {
        String a = newProject(null);
        String b = newProject(null);
        String keyA = fullKey(a);
        String keyB = fullKey(b);
        emailTemplate(keyA);
        String id = send(keyA, request("INFO", "welcome", List.of("EMAIL"), null));
        awaitStatus(keyA, id, "SENT");

        mvc.perform(withKey(get("/notifications/" + id), keyB, null)).andExpect(status().isNotFound());
        mvc.perform(withKey(post("/notifications/" + id + "/retry"), keyB, null)).andExpect(status().isNotFound());
        mvc.perform(withKey(get("/notifications"), keyB, null)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(withKey(get("/templates"), keyB, null)).andExpect(jsonPath("$.length()").value(0));
        // B cannot use A's template
        mvc.perform(withKey(post("/notifications"), keyB, request("INFO", "welcome", List.of("EMAIL"), null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
        // identical idempotency keys in two projects do not collide
        emailTemplate(keyB);
        String idA = send(keyA, request("INFO", "welcome", List.of("EMAIL"), "same"));
        String idB = send(keyB, request("INFO", "welcome", List.of("EMAIL"), "same"));
        assertThat(idA).isNotEqualTo(idB);
    }

    @Test
    void searchPaginatesAndFilters() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        template(key, "n", "IN_APP", "T", null, "B {{name}}");
        for (int i = 0; i < 5; i++) {
            send(key, request("INFO", "n", List.of("IN_APP"), null));
        }
        mvc.perform(withKey(get("/notifications").param("size", "2").param("page", "1"), key, null))
                .andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(withKey(get("/notifications").param("status", "FAILED"), key, null)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(withKey(get("/notifications").param("channel", "IN_APP").param("category", "INFO"), key, null))
                .andExpect(jsonPath("$.total").value(5));
    }

    // ---------- rate limit ----------

    @Test
    void projectRateLimitReturns429() throws Exception {
        String project = newProject(2);
        String key = fullKey(project);
        template(key, "n", "IN_APP", "T", null, "B {{name}}");
        send(key, request("INFO", "n", List.of("IN_APP"), null));
        send(key, request("INFO", "n", List.of("IN_APP"), null));
        mvc.perform(withKey(post("/notifications"), key, request("INFO", "n", List.of("IN_APP"), null)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
    }

    // ---------- OTP ----------

    private static String extractCode(String text) {
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(text);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String capturedOtp(String address) {
        List<EmailMessage> sent = emailsTo(address);
        assertThat(sent).isNotEmpty();
        return extractCode(sent.get(sent.size() - 1).text());
    }

    @Test
    void otpFlowSendsVerifiesOnceAndNeverStoresOrReturnsTheCode() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        String address = "otp-" + UUID.randomUUID() + "@example.com";
        MvcResult sent = mvc.perform(withKey(post("/otp/send"), key, java.util.Map.of("channel", "EMAIL",
                "recipient", address, "purpose", "login"))).andExpect(status().isOk()).andReturn();
        String body = sent.getResponse().getContentAsString();
        String challengeId = json(sent).get("challengeId").asText();
        String code = capturedOtp(address);
        assertThat(body).doesNotContain(code);

        // stored only as a hash
        var stored = otpChallenges.findById(UUID.fromString(challengeId)).orElseThrow();
        assertThat(stored.getCodeHash()).doesNotContain(code).hasSize(64);
        // audit row carries no variables and no contact data
        var audit = notifications.findAll().stream().filter(n -> "otp".equals(n.getTemplateCode())
                && n.getProjectId() != null && n.getRecipientRef().startsWith("otp:")).toList();
        assertThat(audit).isNotEmpty();
        assertThat(audit).allSatisfy(n -> {
            assertThat(n.getVariablesJson()).isNull();
            assertThat(n.getRecipientEmail()).isNull();
            assertThat(n.getBody()).isNull();
        });

        String wrong = code.equals("000000") ? "111111" : "000000";
        mvc.perform(withKey(post("/otp/verify"), key, java.util.Map.of("challengeId", challengeId, "code", wrong)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        mvc.perform(withKey(post("/otp/verify"), key, java.util.Map.of("challengeId", challengeId, "code", code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        mvc.perform(withKey(post("/otp/verify"), key, java.util.Map.of("challengeId", challengeId, "code", code)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void otpResendCooldownAndAttemptLimit() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        String address = "otp-" + UUID.randomUUID() + "@example.com";
        var send = java.util.Map.of("channel", "EMAIL", "recipient", address, "purpose", "login");
        MvcResult sent = mvc.perform(withKey(post("/otp/send"), key, send)).andExpect(status().isOk()).andReturn();
        mvc.perform(withKey(post("/otp/send"), key, send)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RESEND_COOLDOWN"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));

        String challengeId = json(sent).get("challengeId").asText();
        String code = capturedOtp(address);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 3; i++) {
            mvc.perform(withKey(post("/otp/verify"), key, java.util.Map.of("challengeId", challengeId, "code", wrong)))
                    .andExpect(status().isBadRequest());
        }
        // limit reached: even the right code is now refused
        mvc.perform(withKey(post("/otp/verify"), key, java.util.Map.of("challengeId", challengeId, "code", code)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_ATTEMPTS_EXCEEDED"));
    }

    @Test
    void otpExpires() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        String address = "otp-" + UUID.randomUUID() + "@example.com";
        MvcResult sent = mvc.perform(withKey(post("/otp/send"), key, java.util.Map.of("channel", "EMAIL",
                "recipient", address, "purpose", "login"))).andReturn();
        UUID id = UUID.fromString(json(sent).get("challengeId").asText());
        String code = capturedOtp(address);
        var challenge = otpChallenges.findById(id).orElseThrow();
        challenge.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        otpChallenges.save(challenge);
        mvc.perform(withKey(post("/otp/verify"), key, java.util.Map.of("challengeId", id.toString(), "code", code)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
    }

    @Test
    void otpDeliveryFailureReturns502AndLeavesNoUsableChallenge() throws Exception {
        String address = "otp-" + UUID.randomUUID() + "@example.com";
        when(email.send(forAddr(address))).thenReturn(ProviderResult.permanentFailure("MessageRejected"));
        String project = newProject(null);
        String key = fullKey(project);
        long before = otpChallenges.count();
        mvc.perform(withKey(post("/otp/send"), key, java.util.Map.of("channel", "EMAIL",
                "recipient", address, "purpose", "login")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OTP_DELIVERY_FAILED"));
        assertThat(otpChallenges.count()).isEqualTo(before);
    }

    @Test
    void otpUsesProjectTemplateWhenPresentAndCannotCrossProjects() throws Exception {
        String a = newProject(null);
        String keyA = fullKey(a);
        template(keyA, "otp", "SMS", null, null, "A1 code {{code}} valid {{ttlMinutes}} min");
        mvc.perform(withKey(post("/otp/send"), keyA, java.util.Map.of("channel", "SMS", "recipient", phone,
                "purpose", "login"))).andExpect(status().isOk());
        assertThat(smsTo(phone)).hasSize(1);
        assertThat(smsTo(phone).get(0).text()).startsWith("A1 code ").endsWith(" valid 5 min");

        String other = "+9197" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
        MvcResult sent = mvc.perform(withKey(post("/otp/send"), keyA, java.util.Map.of("channel", "SMS",
                "recipient", other, "purpose", "login"))).andReturn();
        String challengeId = json(sent).get("challengeId").asText();
        String keyB = fullKey(newProject(null));
        mvc.perform(withKey(post("/otp/verify"), keyB, java.util.Map.of("challengeId", challengeId, "code", "123456")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    // ---------- misc ----------

    @Test
    void correlationIdIsPropagatedAndErrorsHideInternals() throws Exception {
        MvcResult r = mvc.perform(get("/notifications").header("X-Correlation-Id", "corr-123")).andReturn();
        assertThat(r.getResponse().getHeader("X-Correlation-Id")).isEqualTo("corr-123");
        String project = newProject(null);
        String key = fullKey(project);
        MvcResult bad = mvc.perform(withKey(get("/notifications/not-a-uuid"), key, null)).andExpect(status().isBadRequest()).andReturn();
        assertThat(bad.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at com.");
        assertThat(bad.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void devicesAndPushAreDisabledByDefault() throws Exception {
        String project = newProject(null);
        String key = fullKey(project);
        mvc.perform(withKey(post("/devices"), key, java.util.Map.of("userRef", "user-1", "platform", "android", "token", "tok-1")))
                .andExpect(status().isNoContent());
        mvc.perform(withKey(delete("/devices").param("token", "tok-1"), key, null)).andExpect(status().isNoContent());
        template(key, "p", "PUSH", "T", null, "B {{name}}");
        // push channel flag is off, so requesting it is rejected up front
        mvc.perform(withKey(post("/notifications"), key, request("INFO", "p", List.of("PUSH"), null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHANNEL_DISABLED"));
    }

    @Test
    void openApiSpecIsPublicDocumentedAndComplete() throws Exception {
        JsonNode doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        for (String path : List.of("/notifications", "/notifications/{id}", "/notifications/{id}/retry", "/otp/send",
                "/otp/verify", "/templates", "/templates/{id}/active", "/notification-preferences", "/inbox",
                "/inbox/{id}/read", "/devices", "/admin/projects", "/admin/projects/{code}/keys",
                "/admin/projects/{code}/keys/{keyId}", "/admin/projects/{code}/status", "/admin/dlq", "/admin/dlq/redrive")) {
            assertThat(doc.get("paths").has(path)).as("path " + path).isTrue();
        }
        assertThat(doc.at("/components/securitySchemes/apiKey/name").asText()).isEqualTo("X-Api-Key");
        assertThat(doc.at("/servers/0/url").asText()).isEqualTo("{baseUrl}/api/v1");
        JsonNode create = doc.at("/paths/~1notifications/post");
        assertThat(create.get("summary").asText()).contains("SEND");
        for (String code : List.of("202", "400", "401", "403", "404", "409", "429")) {
            assertThat(create.get("responses").has(code)).as("response " + code).isTrue();
        }
        assertThat(doc.at("/components/schemas/Error/properties").has("correlationId")).isTrue();
        assertThat(doc.at("/components/schemas/CreateNotificationRequest/properties/idempotencyKey/example").asText())
                .isEqualTo("weighment-101-approval-requested");
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
