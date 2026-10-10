package com.a1.notify.core;

import com.a1.notify.web.ApiException;
import java.util.regex.Pattern;

public final class RecipientValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern INDIA_MOBILE = Pattern.compile("^\\+91[6-9]\\d{9}$");

    private RecipientValidator() {
    }

    public static String email(String value) {
        if (value == null || !EMAIL.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_EMAIL", "recipient.email is not a valid email address");
        }
        return value.trim();
    }

    /** E.164 required; +91 numbers must be valid Indian mobile numbers. */
    public static String phone(String value) {
        if (value == null || !E164.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_PHONE", "recipient.phone must be in E.164 format, e.g. +919876543210");
        }
        String phone = value.trim();
        if (phone.startsWith("+91") && !INDIA_MOBILE.matcher(phone).matches()) {
            throw ApiException.badRequest("INVALID_PHONE", "Indian mobile numbers must be +91 followed by 10 digits starting 6-9");
        }
        return phone;
    }
}
