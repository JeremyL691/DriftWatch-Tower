package com.driftwatch.dashboard;

import com.driftwatch.api.AlertResponse;
import com.driftwatch.api.EventResponse;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.RawEventEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class DashboardWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;

    public DashboardWebSocketHandler(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastAlert(QualityAlertEntity alert) {
        messagingTemplate.convertAndSend("/topic/alerts", AlertResponse.from(alert));
    }

    public void broadcastEvent(RawEventEntity event) {
        messagingTemplate.convertAndSend("/topic/events", EventResponse.from(event));
    }
}
