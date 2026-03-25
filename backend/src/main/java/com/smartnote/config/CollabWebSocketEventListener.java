package com.smartnote.config;

import com.smartnote.service.CollabRoomService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class CollabWebSocketEventListener {

    private final CollabRoomService collabRoomService;

    public CollabWebSocketEventListener(CollabRoomService collabRoomService) {
        this.collabRoomService = collabRoomService;
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        collabRoomService.leaveBySessionId(event.getSessionId());
    }
}
