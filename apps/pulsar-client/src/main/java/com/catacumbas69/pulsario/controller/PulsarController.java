package com.catacumbas69.pulsario.controller;

import com.catacumbas69.pulsario.dto.MessageDTO;
import com.catacumbas69.pulsario.dto.ProduceRequest;
import com.catacumbas69.pulsario.service.AuditService;
import com.catacumbas69.pulsario.service.PulsarAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PulsarController {

    @Autowired
    private PulsarAdminService service;

    @Autowired
    private AuditService audit;

    // ─── Auditoria ────────────────────────────────────────────────────────────

    /** Últimos 100 registos de auditoria */
    @GetMapping("/audit")
    public ResponseEntity<?> getAuditLog() {
        try {
            return ResponseEntity.ok(audit.recent(100));
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Tenants ──────────────────────────────────────────────────────────────

    @GetMapping("/tenants")
    public ResponseEntity<?> getTenants() {
        try {
            return ResponseEntity.ok(service.listTenants());
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Namespaces ───────────────────────────────────────────────────────────

    @GetMapping("/namespaces/{tenant}")
    public ResponseEntity<?> getNamespaces(@PathVariable String tenant) {
        try {
            return ResponseEntity.ok(service.listNamespaces(tenant));
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Topics ───────────────────────────────────────────────────────────────

    @GetMapping("/topics/{tenant}/{namespace}")
    public ResponseEntity<?> getTopics(
            @PathVariable String tenant,
            @PathVariable String namespace) {
        try {
            return ResponseEntity.ok(service.listTopics(tenant, namespace));
        } catch (Exception e) {
            return err(e);
        }
    }

    @PostMapping("/topics/{tenant}/{namespace}/{topic}")
    public ResponseEntity<?> createTopic(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            HttpServletRequest req) {
        String fullTopic = "persistent://" + tenant + "/" + namespace + "/" + topic;
        try {
            service.createTopic(fullTopic);
            audit.log(clientIp(req), AuditService.CREATE_TOPIC, fullTopic,
                    null, null, null);
            return ResponseEntity.ok(Map.of("status", "created"));
        } catch (Exception e) {
            return err(e);
        }
    }

    @DeleteMapping("/topics/{tenant}/{namespace}/{topic}")
    public ResponseEntity<?> deleteTopic(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            HttpServletRequest req) {
        String fullTopic = "persistent://" + tenant + "/" + namespace + "/" + topic;
        try {
            service.deleteTopic(fullTopic);
            audit.log(clientIp(req), AuditService.DELETE_TOPIC, fullTopic,
                    null, null, null);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    @GetMapping("/topics/{tenant}/{namespace}/{topic}/stats")
    public ResponseEntity<?> getStats(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic) {
        try {
            TopicStats stats = service.getTopicStats(
                    "persistent://" + tenant + "/" + namespace + "/" + topic);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Subscriptions ────────────────────────────────────────────────────────

    @GetMapping("/topics/{tenant}/{namespace}/{topic}/subscriptions")
    public ResponseEntity<?> getSubscriptions(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic) {
        try {
            return ResponseEntity.ok(service.listSubscriptions(
                    "persistent://" + tenant + "/" + namespace + "/" + topic));
        } catch (Exception e) {
            return err(e);
        }
    }

    @PostMapping("/topics/{tenant}/{namespace}/{topic}/subscriptions/{subscription}")
    public ResponseEntity<?> createSubscription(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            @PathVariable String subscription,
            HttpServletRequest req) {
        String fullTopic = "persistent://" + tenant + "/" + namespace + "/" + topic;
        try {
            service.createSubscription(fullTopic, subscription);
            audit.log(clientIp(req), AuditService.CREATE_SUBSCRIPTION, fullTopic,
                    null, null, Map.of("subscription", subscription));
            return ResponseEntity.ok(Map.of("status", "created"));
        } catch (Exception e) {
            return err(e);
        }
    }

    @DeleteMapping("/topics/{tenant}/{namespace}/{topic}/subscriptions/{subscription}")
    public ResponseEntity<?> deleteSubscription(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            @PathVariable String subscription,
            HttpServletRequest req) {
        String fullTopic = "persistent://" + tenant + "/" + namespace + "/" + topic;
        try {
            service.deleteSubscription(fullTopic, subscription);
            audit.log(clientIp(req), AuditService.DELETE_SUBSCRIPTION, fullTopic,
                    null, null, Map.of("subscription", subscription));
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Produce ──────────────────────────────────────────────────────────────

    @PostMapping("/topics/{tenant}/{namespace}/{topic}/produce")
    public ResponseEntity<?> produce(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            @RequestBody ProduceRequest body,
            HttpServletRequest req) {
        String fullTopic = "persistent://" + tenant + "/" + namespace + "/" + topic;
        try {
            String msgId = service.produce(fullTopic, body.payload(), body.key());
            audit.log(clientIp(req), AuditService.PRODUCE, fullTopic,
                    body.payload(), body.key(),
                    body.properties() != null ? Map.of("properties", body.properties()) : null);
            return ResponseEntity.ok(Map.of("messageId", msgId));
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Peek Messages ────────────────────────────────────────────────────────

    @GetMapping("/topics/{tenant}/{namespace}/{topic}/peek/{subscription}")
    public ResponseEntity<?> peekMessages(
            @PathVariable String tenant,
            @PathVariable String namespace,
            @PathVariable String topic,
            @PathVariable String subscription,
            @RequestParam(defaultValue = "10") int count,
            HttpServletRequest req) {
        String fullTopic = "persistent://" + tenant + "/" + namespace + "/" + topic;
        try {
            List<MessageDTO> msgs = service.peekMessages(fullTopic, subscription, count);
            audit.log(clientIp(req), AuditService.PEEK_MESSAGES, fullTopic,
                    null, null, Map.of("subscription", subscription, "count", count));
            return ResponseEntity.ok(msgs);
        } catch (Exception e) {
            return err(e);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> err(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.internalServerError().body(Map.of("error", msg));
    }

    /** IP do cliente (considera X-Forwarded-For do Ingress/Route) */
    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
