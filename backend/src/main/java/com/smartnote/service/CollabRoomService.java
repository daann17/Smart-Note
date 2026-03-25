package com.smartnote.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollabRoomService {

    private final Map<Long, ConcurrentHashMap<String, Participant>> rooms = new ConcurrentHashMap<>();
    private final Map<String, Participant> sessionIndex = new ConcurrentHashMap<>();

    public int join(Long noteId, String sessionId, String clientId, String user) {
        leaveBySessionId(sessionId);

        Participant participant = new Participant(noteId, sessionId, clientId, user);
        ConcurrentHashMap<String, Participant> room = rooms.computeIfAbsent(noteId, key -> new ConcurrentHashMap<>());
        room.put(sessionId, participant);
        sessionIndex.put(sessionId, participant);

        return Math.max(0, room.size() - 1);
    }

    public void leave(Long noteId, String sessionId) {
        Participant participant = sessionIndex.remove(sessionId);
        if (participant == null) {
            return;
        }

        ConcurrentHashMap<String, Participant> room = rooms.get(noteId);
        if (room == null) {
            return;
        }

        room.remove(sessionId);
        if (room.isEmpty()) {
            rooms.remove(noteId);
        }
    }

    public void leaveBySessionId(String sessionId) {
        Participant participant = sessionIndex.get(sessionId);
        if (participant == null) {
            return;
        }

        leave(participant.noteId(), sessionId);
    }

    private record Participant(Long noteId, String sessionId, String clientId, String user) {
    }
}
