package com.smartnote.config;

import com.smartnote.service.CollabAccessService;
import com.smartnote.util.JwtUtil;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CollabChannelInterceptor implements ChannelInterceptor {

    private static final Pattern COLLAB_DESTINATION_PATTERN = Pattern.compile("^/(?:app|topic)/collab/(\\d+)$");

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final CollabAccessService collabAccessService;

    public CollabChannelInterceptor(
            JwtUtil jwtUtil,
            UserDetailsService userDetailsService,
            CollabAccessService collabAccessService
    ) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.collabAccessService = collabAccessService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) || StompCommand.SEND.equals(accessor.getCommand())) {
            authorizeCollabDestination(accessor);
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AccessDeniedException("协同编辑需要先登录");
        }

        String token = authorization.substring(7);

        try {
            String username = jwtUtil.extractUsername(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (!userDetails.isEnabled() || !jwtUtil.validateToken(token, userDetails.getUsername())) {
                throw new AccessDeniedException("登录状态已失效");
            }

            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            ));
        } catch (Exception exception) {
            throw new AccessDeniedException("登录状态已失效");
        }
    }

    private void authorizeCollabDestination(StompHeaderAccessor accessor) {
        Long noteId = parseNoteId(accessor.getDestination());
        if (noteId == null) {
            return;
        }

        Principal principal = accessor.getUser();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new AccessDeniedException("协同编辑需要先登录");
        }

        String shareToken = accessor.getFirstNativeHeader("x-share-token");
        if (!collabAccessService.canAccess(principal.getName(), noteId, shareToken)) {
            throw new AccessDeniedException("无权加入该协同编辑会话");
        }
    }

    private Long parseNoteId(String destination) {
        if (destination == null) {
            return null;
        }

        Matcher matcher = COLLAB_DESTINATION_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return null;
        }

        return Long.parseLong(matcher.group(1));
    }
}
