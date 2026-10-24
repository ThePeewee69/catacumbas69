package com.catacumbas69.pulsarclient.service;

import com.catacumbas69.pulsarclient.model.AuditEntry;
import com.catacumbas69.pulsarclient.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    // Acções disponíveis
    public static final String PRODUCE             = "PRODUCE";
    public static final String CREATE_TOPIC        = "CREATE_TOPIC";
    public static final String DELETE_TOPIC        = "DELETE_TOPIC";
    public static final String CREATE_SUBSCRIPTION = "CREATE_SUBSCRIPTION";
    public static final String DELETE_SUBSCRIPTION = "DELETE_SUBSCRIPTION";
    public static final String PEEK_MESSAGES       = "PEEK_MESSAGES";

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    /**
     * Grava um registo de auditoria no MongoDB.
     * Falhas são apenas logadas — não propagadas, para não interromper a operação principal.
     */
    public void log(String user, String action, String topic,
                    String messagePayload, String messageKey,
                    Map<String, Object> topicProperties) {
        try {
            AuditEntry entry = new AuditEntry(user, action, topic,
                    messagePayload, messageKey, topicProperties);
            repo.save(entry);
            log.info("Audit gravado: user={} action={} topic={}", user, action, topic);
        } catch (Exception e) {
            log.warn("Falha ao gravar audit no MongoDB: {}", e.getMessage());
        }
    }

    /**
     * Últimas N entradas de auditoria (para a UI).
     */
    public List<AuditEntry> recent(int limit) {
        return repo.findAllByOrderByTimestampDesc(PageRequest.of(0, limit)).getContent();
    }
}
