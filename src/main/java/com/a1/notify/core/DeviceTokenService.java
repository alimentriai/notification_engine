package com.a1.notify.core;

import com.a1.notify.domain.DeviceTokenEntity;
import com.a1.notify.repository.DeviceTokenRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers mobile device tokens for push. Delivery stays disabled until a PushProvider is configured. */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository tokens;

    public DeviceTokenService(DeviceTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional
    public void register(Long projectId, String userRef, String platform, String token) {
        DeviceTokenEntity entity = tokens.findByProjectIdAndToken(projectId, token).orElseGet(DeviceTokenEntity::new);
        entity.setProjectId(projectId);
        entity.setUserRef(userRef);
        entity.setPlatform(platform.toUpperCase());
        entity.setToken(token);
        entity.setActive(true);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        tokens.save(entity);
    }

    @Transactional
    public void unregister(Long projectId, String token) {
        tokens.findByProjectIdAndToken(projectId, token).ifPresent(t -> t.setActive(false));
    }
}
