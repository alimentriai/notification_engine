package com.a1.notify.core;

import com.a1.notify.api.dto.NotificationView;
import com.a1.notify.api.dto.PageResponse;
import com.a1.notify.domain.Channel;
import com.a1.notify.domain.NotificationEntity;
import com.a1.notify.repository.NotificationRepository;
import com.a1.notify.web.ApiException;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications for one end user of a project. The calling project passes the user reference. */
@Service
public class InboxService {

    private final NotificationRepository notifications;

    public InboxService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(Long projectId, String recipientRef, boolean unreadOnly, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Specification<NotificationEntity> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("projectId"), projectId));
            p.add(cb.equal(root.get("recipientRef"), recipientRef));
            p.add(cb.equal(root.get("channel"), Channel.IN_APP));
            p.add(cb.equal(root.get("status"), com.a1.notify.domain.NotificationStatus.DELIVERED));
            if (unreadOnly) {
                p.add(cb.isNull(root.get("readAt")));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<NotificationEntity> result = notifications.findAll(spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(result.getContent().stream().map(NotificationView::of).toList(),
                result.getTotalElements(), safePage, safeSize);
    }

    @Transactional
    public NotificationView markRead(Long projectId, String recipientRef, UUID id) {
        NotificationEntity n = notifications.findByIdAndProjectId(id, projectId)
                .filter(x -> x.getChannel() == Channel.IN_APP && x.getRecipientRef().equals(recipientRef))
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        if (n.getReadAt() == null) {
            n.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
            n.setUpdatedAt(n.getReadAt());
        }
        return NotificationView.of(n);
    }
}
