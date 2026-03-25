package com.smartnote.controller;

import com.smartnote.service.CollabRoomService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

@Controller
public class CollabController {

    private final CollabRoomService collabRoomService;

    public CollabController(CollabRoomService collabRoomService) {
        this.collabRoomService = collabRoomService;
    }

    @MessageMapping("/collab/{noteId}")
    @SendTo("/topic/collab/{noteId}")
    public Map<String, Object> handleCollabMessage(
            @DestinationVariable Long noteId,
            @Payload Map<String, Object> message,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Map<String, Object> response = new HashMap<>(message);
        String type = stringValue(message.get("type"));
        String sessionId = headerAccessor.getSessionId();

        if ("join".equals(type)) {
            int peerCount = collabRoomService.join(
                    noteId,
                    sessionId,
                    stringValue(message.get("clientId")),
                    stringValue(message.get("user"))
            );
            response.put("peerCount", peerCount);
        } else if ("leave".equals(type)) {
            collabRoomService.leave(noteId, sessionId);
        }

        return response;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
