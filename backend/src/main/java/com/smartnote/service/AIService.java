package com.smartnote.service;

import com.smartnote.entity.Note;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Service
public class AIService {

    private final ChatClient chatClient;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;

    @Autowired
    public AIService(ChatClient.Builder chatClientBuilder, NoteRepository noteRepository, UserRepository userRepository) {
        this.chatClient = chatClientBuilder.build();
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
    }

    /**
     * 为笔记生成摘要并保存
     */
    @Transactional
    public Note generateSummary(Long noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));

        String content = note.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("Note content is empty");
        }

        String prompt = "请为以下笔记内容生成一段简短的摘要（不超过100字）：\n\n" + content;

        try {
            String summary = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            
            note.setSummary(summary);
            return noteRepository.save(note);
        } catch (Exception e) {
            // 如果 API 调用失败，提供友好的错误提示
            throw new RuntimeException("Failed to generate summary: " + e.getMessage(), e);
        }
    }

    /**
     * 为笔记生成智能标签建议
     */
    public java.util.List<String> suggestTags(Long noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));

        String content = note.getContent();
        if (content == null || content.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }

        String prompt = "请仔细阅读以下笔记内容，并提取出最能概括其核心主题的3到5个标签（Tag）。\n" +
                "要求：\n" +
                "1. 只返回标签词语，使用半角逗号分隔，例如：Java,Spring Boot,人工智能\n" +
                "2. 不要包含任何其他解释性文本或标点符号。\n" +
                "3. 标签要尽量简短、精准。\n\n" +
                "笔记内容：\n" + content;

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            
            if (response == null || response.trim().isEmpty()) {
                return java.util.Collections.emptyList();
            }

            return java.util.Arrays.stream(response.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Failed to suggest tags: " + e.getMessage(), e);
        }
    }

    /**
     * 与 AI 助手对话 (支持上下文和流式输出)
     */
    public Flux<String> chat(String message, Long noteId, String username) {
        String systemPrompt = "你是一个智能笔记助手，名为 SmartNote AI。你的任务是帮助用户总结、解答问题、提供灵感。\n" +
                "请使用 Markdown 格式进行回复，以便于在前端良好展示。";
        
        if (noteId != null) {
            Note note = noteRepository.findById(noteId).orElse(null);
            if (note != null && note.getNotebook().getUser().getUsername().equals(username)) {
                systemPrompt += "\n\n当前用户正在查看的笔记内容如下：\n" +
                        "【标题】：" + note.getTitle() + "\n" +
                        "【内容】：" + note.getContent() + "\n\n" +
                        "请优先基于以上笔记内容回答用户的问题。如果用户的问题与笔记无关，你可以自由回答。";
            }
        } else {
            // 简单的全局搜索 RAG 逻辑
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            java.util.List<Note> relatedNotes = noteRepository.searchNotes(user.getId(), message);
            if (relatedNotes != null && !relatedNotes.isEmpty()) {
                systemPrompt += "\n\n以下是用户的知识库中可能相关的笔记片段：\n";
                int limit = Math.min(3, relatedNotes.size());
                for (int i = 0; i < limit; i++) {
                    Note note = relatedNotes.get(i);
                    systemPrompt += "【笔记标题】：" + note.getTitle() + "\n";
                    String content = note.getContent() == null ? "" : note.getContent();
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    systemPrompt += "【内容片段】：" + content + "\n\n";
                }
                systemPrompt += "请结合以上相关笔记内容，回答用户的问题。如果问题与笔记无关，你可以自由回答。";
            }
        }

        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(message)
                    .stream()
                    .content();
        } catch (Exception e) {
            throw new RuntimeException("Failed to chat with AI: " + e.getMessage(), e);
        }
    }
}
