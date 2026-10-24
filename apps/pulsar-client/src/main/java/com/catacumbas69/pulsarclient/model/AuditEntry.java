package com.catacumbas69.pulsarclient.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Registo de auditoria — gravado na collection "audit_logs" da base de dados "catacumbas69".
 *
 * Campos:
 *  - timestamp      : quando ocorreu a acção
 *  - user           : IP do cliente HTTP (sem autenticação na app)
 *  - action         : acção realizada (PRODUCE, CREATE_TOPIC, DELETE_TOPIC, etc.)
 *  - topic          : tópico Pulsar no formato persistent://tenant/namespace/topic
 *  - messagePayload : conteúdo da mensagem (apenas em PRODUCE/PEEK)
 *  - messageKey     : chave da mensagem Pulsar (pode ser null)
 *  - topicProperties: propriedades adicionais do tópico (subscrição, contagem, etc.)
 */
@Document(collection = "audit_logs")
public class AuditEntry {

    @Id
    private String id;

    @Indexed
    private Instant timestamp;

    @Indexed
    private String user;

    @Indexed
    private String action;

    @Indexed
    private String topic;

    private String messagePayload;
    private String messageKey;
    private Map<String, Object> topicProperties;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public AuditEntry() {}

    public AuditEntry(String user, String action, String topic,
                      String messagePayload, String messageKey,
                      Map<String, Object> topicProperties) {
        this.timestamp = Instant.now();
        this.user = user;
        this.action = action;
        this.topic = topic;
        this.messagePayload = messagePayload;
        this.messageKey = messageKey;
        this.topicProperties = topicProperties;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUser() { return user; }
    public String getAction() { return action; }
    public String getTopic() { return topic; }
    public String getMessagePayload() { return messagePayload; }
    public String getMessageKey() { return messageKey; }
    public Map<String, Object> getTopicProperties() { return topicProperties; }
}
