package com.a1.notify.core;

import com.a1.notify.domain.Category;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.PreferenceEntity;
import com.a1.notify.repository.PreferenceRepository;
import com.a1.notify.web.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent rules: mandatory categories (OTP, VERIFICATION, SYSTEM) ignore opt-outs; opt-in categories
 * (MARKETING) are sent only with explicit consent; everything else is on unless the user turned it off.
 */
@Service
public class PreferenceService {

    private final PreferenceRepository preferences;

    public PreferenceService(PreferenceRepository preferences) {
        this.preferences = preferences;
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(Long projectId, String userRef, Channel channel, Category category) {
        if (category.isMandatory()) {
            return true;
        }
        Optional<PreferenceEntity> pref = preferences.findByProjectIdAndUserRefAndChannelAndCategory(
                projectId, userRef, channel, category);
        if (category.isOptIn()) {
            return pref.map(PreferenceEntity::isEnabled).orElse(false);
        }
        return pref.map(PreferenceEntity::isEnabled).orElse(true);
    }

    @Transactional(readOnly = true)
    public List<PreferenceEntity> list(Long projectId, String userRef) {
        return preferences.findByProjectIdAndUserRef(projectId, userRef);
    }

    @Transactional
    public void upsert(Long projectId, String userRef, Channel channel, Category category, boolean enabled) {
        if (category.isMandatory() && !enabled) {
            throw ApiException.badRequest("MANDATORY_CATEGORY", category + " notifications cannot be disabled");
        }
        PreferenceEntity pref = preferences
                .findByProjectIdAndUserRefAndChannelAndCategory(projectId, userRef, channel, category)
                .orElseGet(PreferenceEntity::new);
        pref.setProjectId(projectId);
        pref.setUserRef(userRef);
        pref.setChannel(channel);
        pref.setCategory(category);
        pref.setEnabled(enabled);
        pref.setConsentAt(OffsetDateTime.now(ZoneOffset.UTC));
        preferences.save(pref);
    }
}
