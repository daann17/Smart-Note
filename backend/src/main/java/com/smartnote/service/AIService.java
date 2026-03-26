package com.smartnote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartnote.dto.AIChatMessageRequest;
import com.smartnote.entity.Note;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_RELATED_NOTES = 4;
    private static final int MAX_RELATED_RESULTS_PER_QUERY = 5;
    private static final int MAX_NOTE_EXCERPT_LENGTH = 700;
    private static final int MAX_CURRENT_NOTE_LENGTH = 2000;
    private static final DateTimeFormatter NOTE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern ENGLISH_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{1,23}");
    private static final Pattern CHINESE_TOKEN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,12}");
    private static final Set<String> STOP_TERMS = Set.of(
            "请问", "帮我", "一下", "一个", "一些", "有关", "关于", "如何", "怎么", "为什么", "哪些",
            "什么", "有没有", "是否", "这个", "那个", "这里", "那里", "知识库", "笔记", "内容", "整理",
            "总结", "说明", "解释", "介绍", "分析", "告诉", "帮忙", "问题", "问答", "相关", "当前", "里面"
    );

    private final ChatClient chatClient;
    private final WebClient aiWebClient;
    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String aiApiKey;
    private final String aiModel;

    public AIService(
            ChatClient.Builder chatClientBuilder,
            WebClient.Builder webClientBuilder,
            NoteRepository noteRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key:}") String aiApiKey,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String aiBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String aiModel
    ) {
        this.chatClient = chatClientBuilder.build();
        this.aiWebClient = webClientBuilder
                .baseUrl(normalizeBaseUrl(aiBaseUrl))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + normalizeOptionalText(aiApiKey))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.aiApiKey = normalizeOptionalText(aiApiKey);
        this.aiModel = normalizeOptionalText(aiModel).isBlank() ? "deepseek-chat" : normalizeOptionalText(aiModel);
    }

    @Transactional
    public Note generateSummary(Long noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));

        String content = note.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("Note content is empty");
        }

        String prompt = """
                请为下面的笔记生成一段简洁摘要。
                要求：
                1. 使用简体中文
                2. 控制在 100 字以内
                3. 只输出摘要正文，不要附加标题或解释

                笔记内容：
                """ + "\n" + content;

        try {
            String summary = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            note.setSummary(summary);
            return noteRepository.save(note);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to generate summary: " + exception.getMessage(), exception);
        }
    }

    public List<String> suggestTags(Long noteId) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));

        String content = note.getContent();
        if (content == null || content.trim().isEmpty()) {
            return List.of();
        }

        String prompt = """
                请根据下面的笔记内容提取 3 到 5 个最合适的标签。
                要求：
                1. 标签尽量短，突出主题或关键概念
                2. 仅返回标签本身
                3. 使用英文逗号分隔
                4. 不要添加任何解释、编号或额外文字

                笔记内容：
                """ + "\n" + content;

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.trim().isEmpty()) {
                return List.of();
            }

            return Arrays.stream(response.split("[,，\\n]"))
                    .map(String::trim)
                    .filter((value) -> !value.isEmpty())
                    .distinct()
                    .limit(5)
                    .toList();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to suggest tags: " + exception.getMessage(), exception);
        }
    }

    public Flux<String> chat(
            String message,
            Long noteId,
            List<AIChatMessageRequest> history,
            String username
    ) {
        String normalizedMessage = normalizeRequiredMessage(message);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Note currentNote = resolveCurrentNote(noteId, username);
        List<AIChatMessageRequest> sanitizedHistory = sanitizeHistory(history);
        List<Note> relatedNotes = retrieveRelevantNotes(user.getId(), currentNote, normalizedMessage, sanitizedHistory);

        String systemPrompt = buildSystemPrompt(currentNote, relatedNotes);
        String userPrompt = buildUserPrompt(normalizedMessage, sanitizedHistory);

        return Flux.defer(() -> streamChatCompletion(systemPrompt, userPrompt));
    }

    private Note resolveCurrentNote(Long noteId, String username) {
        if (noteId == null) {
            return null;
        }

        return noteRepository.findById(noteId)
                .filter((note) -> note.getNotebook() != null
                        && note.getNotebook().getUser() != null
                        && username.equals(note.getNotebook().getUser().getUsername()))
                .orElse(null);
    }

    private List<AIChatMessageRequest> sanitizeHistory(List<AIChatMessageRequest> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        int startIndex = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        return history.subList(startIndex, history.size()).stream()
                .filter(Objects::nonNull)
                .map((item) -> {
                    AIChatMessageRequest sanitized = new AIChatMessageRequest();
                    sanitized.setRole(normalizeRole(item.getRole()));
                    sanitized.setContent(normalizeOptionalText(item.getContent()));
                    return sanitized;
                })
                .filter((item) -> !item.getContent().isBlank())
                .toList();
    }

    private String normalizeRole(String value) {
        if (value == null) {
            return "user";
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "assistant".equals(normalized) || "ai".equals(normalized) ? "assistant" : "user";
    }

    private String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeRequiredMessage(String message) {
        String normalized = normalizeOptionalText(message);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
        return normalized;
    }

    private List<Note> retrieveRelevantNotes(
            Long userId,
            Note currentNote,
            String message,
            List<AIChatMessageRequest> history
    ) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.addAll(extractHeuristicQueries(message));
        queries.addAll(extractHeuristicQueriesFromHistory(history));
        queries.addAll(generateSearchQueriesWithAi(message, history));

        if (queries.isEmpty()) {
            queries.add(message);
        }

        Map<Long, RetrievalCandidate> candidates = new java.util.HashMap<>();
        for (String query : queries) {
            List<Note> matchedNotes = noteRepository.searchNotes(userId, query);
            int limit = Math.min(MAX_RELATED_RESULTS_PER_QUERY, matchedNotes.size());
            for (int index = 0; index < limit; index += 1) {
                Note note = matchedNotes.get(index);
                if (currentNote != null && Objects.equals(currentNote.getId(), note.getId())) {
                    continue;
                }

                int score = MAX_RELATED_RESULTS_PER_QUERY - index;
                candidates.compute(note.getId(), (_key, previous) -> {
                    if (previous == null) {
                        return new RetrievalCandidate(note, score);
                    }
                    previous.score += score;
                    return previous;
                });
            }
        }

        return candidates.values().stream()
                .sorted(Comparator
                        .comparingInt(RetrievalCandidate::score).reversed()
                        .thenComparing((candidate) -> candidate.note.getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_RELATED_NOTES)
                .map((candidate) -> candidate.note)
                .toList();
    }

    private List<String> extractHeuristicQueriesFromHistory(List<AIChatMessageRequest> history) {
        if (history.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        history.stream()
                .filter((item) -> "user".equals(item.getRole()))
                .skip(Math.max(0, history.size() - 2L))
                .forEach((item) -> queries.addAll(extractHeuristicQueries(item.getContent())));
        return queries.stream().limit(4).toList();
    }

    private List<String> extractHeuristicQueries(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String cleaned = text
                .replace('，', ' ')
                .replace('。', ' ')
                .replace('？', ' ')
                .replace('！', ' ')
                .replace('、', ' ')
                .replace('：', ' ')
                .replace(':', ' ')
                .replace('；', ' ')
                .replace(';', ' ')
                .replace('\n', ' ');

        Matcher englishMatcher = ENGLISH_TOKEN_PATTERN.matcher(cleaned);
        while (englishMatcher.find()) {
            String token = englishMatcher.group().trim();
            if (token.length() >= 2) {
                queries.add(token);
            }
        }

        Matcher chineseMatcher = CHINESE_TOKEN_PATTERN.matcher(cleaned);
        while (chineseMatcher.find()) {
            String token = stripStopTerms(chineseMatcher.group().trim());
            if (token.length() >= 2 && !STOP_TERMS.contains(token)) {
                queries.add(token);
            }
        }

        for (String part : cleaned.split("\\s+")) {
            String token = stripStopTerms(part.trim());
            if (token.length() >= 2 && token.length() <= 24 && !STOP_TERMS.contains(token)) {
                queries.add(token);
            }
        }

        if (queries.isEmpty() && text.trim().length() <= 24) {
            queries.add(text.trim());
        }

        return queries.stream().limit(6).toList();
    }

    private String stripStopTerms(String token) {
        String result = token;
        for (String stopTerm : STOP_TERMS) {
            result = result.replace(stopTerm, " ");
        }
        return result.replaceAll("\\s+", "").trim();
    }

    private List<String> generateSearchQueriesWithAi(String message, List<AIChatMessageRequest> history) {
        String prompt = """
                你是知识库检索查询改写助手。
                请根据用户当前问题和最近对话，提取 1 到 4 个适合在笔记知识库中检索的关键词或短语。

                输出要求：
                1. 只返回关键词或短语
                2. 使用英文逗号分隔
                3. 不要解释，不要编号
                4. 每个关键词尽量不超过 12 个字

                最近对话：
                """ + "\n" + buildHistoryText(history) + "\n\n当前问题：\n" + message;

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                return List.of();
            }

            return Arrays.stream(response.split("[,，\\n]"))
                    .map(String::trim)
                    .filter((value) -> !value.isEmpty())
                    .filter((value) -> value.length() <= 24)
                    .distinct()
                    .limit(4)
                    .toList();
        } catch (Exception exception) {
            log.debug("Failed to generate AI search queries, fallback to heuristics", exception);
            return List.of();
        }
    }

    private Flux<String> streamChatCompletion(String systemPrompt, String userPrompt) {
        ensureAiConfigured();

        ParameterizedTypeReference<ServerSentEvent<String>> streamEventType =
                new ParameterizedTypeReference<ServerSentEvent<String>>() {};

        return aiWebClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(buildChatCompletionRequest(systemPrompt, userPrompt, true))
                .exchangeToFlux((response) -> {
                    HttpStatusCode statusCode = response.statusCode();
                    if (statusCode.isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany((providerResponse) -> {
                                    log.error("AI provider rejected chat stream with status {}: {}", statusCode.value(), providerResponse);
                                    return Flux.error(new ResponseStatusException(
                                            HttpStatus.BAD_GATEWAY,
                                            buildAiProviderErrorMessage(statusCode, providerResponse)
                                    ));
                                });
                    }

                    return response.bodyToFlux(streamEventType)
                            .mapNotNull(ServerSentEvent::data)
                            .map(this::extractAssistantDelta)
                            .filter((chunk) -> !chunk.isBlank());
                })
                .onErrorMap(
                        (exception) -> !(exception instanceof ResponseStatusException),
                        (exception) -> {
                            log.error("Failed to stream AI provider response", exception);
                            return new ResponseStatusException(
                                    HttpStatus.BAD_GATEWAY,
                                    "AI provider stream is unavailable",
                                    exception
                            );
                        }
                );
    }

    private Map<String, Object> buildChatCompletionRequest(String systemPrompt, String userPrompt, boolean stream) {
        return Map.of(
                "model", aiModel,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "stream", stream
        );
    }

    private String extractAssistantContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务未返回有效内容");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务未返回有效内容");
            }
            return content;
        } catch (IOException exception) {
            log.error("Failed to parse AI provider response: {}", responseBody, exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务返回了无法解析的数据", exception);
        }
    }

    private String extractAssistantDelta(String eventPayload) {
        String payload = normalizeOptionalText(eventPayload);
        if (payload.isBlank() || "[DONE]".equals(payload)) {
            return "";
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                String providerMessage = errorNode.path("message").asText(payload).trim();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, providerMessage);
            }

            JsonNode choice = root.path("choices").path(0);
            String deltaContent = choice.path("delta").path("content").asText("");
            if (!deltaContent.isBlank()) {
                return deltaContent;
            }

            String messageContent = choice.path("message").path("content").asText("");
            if (!messageContent.isBlank()) {
                return messageContent;
            }

            return "";
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Failed to parse AI stream chunk: {}", payload, exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider stream parse failed", exception);
        }
    }

    private void ensureAiConfigured() {
        if (aiApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务未配置 API Key");
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized.isBlank()) {
            return "https://api.deepseek.com";
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String readResponseBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            log.warn("Failed to read AI provider error body", exception);
            return "";
        }
    }

    private String buildAiProviderErrorMessage(HttpStatusCode statusCode, String providerResponse) {
        if (statusCode.value() == 401 || statusCode.value() == 403) {
            return "AI 服务调用被模型平台拒绝，请检查平台兼容配置";
        }
        if (statusCode.value() == 429) {
            return "AI 服务调用过于频繁，请稍后重试";
        }
        if (statusCode.is5xxServerError()) {
            return "AI 服务上游暂时不可用，请稍后重试";
        }
        if (providerResponse != null && !providerResponse.isBlank()) {
            return "AI 服务调用失败：" + providerResponse;
        }
        return "AI 服务调用失败，请稍后重试";
    }

    private String buildSystemPrompt(Note currentNote, List<Note> relatedNotes) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是 SmartNote AI，负责基于用户个人知识库进行问答。
                回答要求：
                1. 使用简体中文 Markdown。
                2. 优先依据“当前笔记上下文”和“知识库检索结果”回答。
                3. 如果知识库信息不足，要明确说明“笔记中没有足够信息”，再补充通用建议。
                4. 不要编造不存在的笔记细节、标题、日期或结论。
                5. 回答尽量先给结论，再给要点或下一步建议。
                6. 如果引用了知识库内容，最后单独加一行：参考笔记：标题1；标题2。
                """);

        if (currentNote != null) {
            builder.append("\n\n【当前笔记上下文】\n");
            builder.append("标题：").append(defaultTitle(currentNote)).append('\n');
            builder.append("更新时间：").append(formatNoteTime(currentNote)).append('\n');
            builder.append("内容：\n").append(clipText(currentNote.getContent(), MAX_CURRENT_NOTE_LENGTH)).append('\n');
        }

        if (!relatedNotes.isEmpty()) {
            builder.append("\n【知识库检索结果】\n");
            for (int index = 0; index < relatedNotes.size(); index += 1) {
                Note note = relatedNotes.get(index);
                builder.append(index + 1)
                        .append(". 标题：")
                        .append(defaultTitle(note))
                        .append("（更新时间：")
                        .append(formatNoteTime(note))
                        .append("）\n");
                builder.append("摘要片段：")
                        .append(clipText(buildNoteSnippet(note), MAX_NOTE_EXCERPT_LENGTH))
                        .append("\n\n");
            }
        }

        return builder.toString();
    }

    private String buildUserPrompt(String message, List<AIChatMessageRequest> history) {
        String historyText = buildHistoryText(history);
        if (historyText.isBlank()) {
            return "用户当前问题：\n" + message;
        }

        return """
                最近对话：
                """ + "\n" + historyText + "\n\n用户当前问题：\n" + message;
    }

    private String buildHistoryText(List<AIChatMessageRequest> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        return history.stream()
                .map((item) -> ("assistant".equals(item.getRole()) ? "助手" : "用户") + "："
                        + clipText(item.getContent(), 300))
                .collect(Collectors.joining("\n"));
    }

    private String buildNoteSnippet(Note note) {
        if (note.getSummary() != null && !note.getSummary().isBlank()) {
            return note.getSummary().trim();
        }

        if (note.getContent() != null && !note.getContent().isBlank()) {
            return note.getContent().trim();
        }

        return "该笔记暂无正文内容。";
    }

    private String clipText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }

        String normalized = text.replace("\r", "").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String defaultTitle(Note note) {
        if (note.getTitle() == null || note.getTitle().isBlank()) {
            return "未命名笔记";
        }
        return note.getTitle().trim();
    }

    private String formatNoteTime(Note note) {
        if (note.getUpdatedAt() == null) {
            return "--";
        }
        return NOTE_TIME_FORMATTER.format(note.getUpdatedAt());
    }

    private static final class RetrievalCandidate {
        private final Note note;
        private int score;

        private RetrievalCandidate(Note note, int score) {
            this.note = note;
            this.score = score;
        }

        private int score() {
            return score;
        }
    }
}
