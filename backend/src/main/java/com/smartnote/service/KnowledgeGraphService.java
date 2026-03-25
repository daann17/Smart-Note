package com.smartnote.service;

import com.smartnote.dto.KnowledgeGraphResponse;
import com.smartnote.entity.Note;
import com.smartnote.entity.Notebook;
import com.smartnote.entity.Tag;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class KnowledgeGraphService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_RELATED_NOTE_LINKS = 120;

    private final UserRepository userRepository;
    private final NotebookRepository notebookRepository;
    private final NoteRepository noteRepository;

    public KnowledgeGraphService(
            UserRepository userRepository,
            NotebookRepository notebookRepository,
            NoteRepository noteRepository
    ) {
        this.userRepository = userRepository;
        this.notebookRepository = notebookRepository;
        this.noteRepository = noteRepository;
    }

    @Transactional(readOnly = true)
    public KnowledgeGraphResponse getKnowledgeGraph(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Notebook> notebooks = notebookRepository.findByUserIdAndStatusNotOrderByCreatedAtDesc(user.getId(), "TRASH");
        List<Note> notes = noteRepository.findByNotebookUserIdAndStatusNotOrderByUpdatedAtDesc(user.getId(), "TRASH");

        Map<Long, Integer> notebookNoteCount = new HashMap<>();
        Map<String, List<Note>> tagToNotes = new LinkedHashMap<>();
        List<KnowledgeGraphResponse.Node> nodes = new ArrayList<>();
        List<KnowledgeGraphResponse.Link> links = new ArrayList<>();

        for (Note note : notes) {
            Long notebookId = note.getNotebook().getId();
            notebookNoteCount.merge(notebookId, 1, Integer::sum);

            for (Tag tag : note.getTags()) {
                tagToNotes.computeIfAbsent(tag.getName(), key -> new ArrayList<>()).add(note);
            }
        }

        for (Notebook notebook : notebooks) {
            int noteCount = notebookNoteCount.getOrDefault(notebook.getId(), 0);
            nodes.add(new KnowledgeGraphResponse.Node(
                    notebookNodeId(notebook.getId()),
                    notebook.getName(),
                    "notebook",
                    26 + Math.min(noteCount * 3, 14),
                    notebook.getDescription() != null && !notebook.getDescription().isBlank()
                            ? notebook.getDescription()
                            : "包含 " + noteCount + " 篇笔记",
                    "/notebook/" + notebook.getId(),
                    notebook.getId(),
                    null,
                    null
            ));
        }

        for (Note note : notes) {
            String notebookName = note.getNotebook() != null ? note.getNotebook().getName() : "";
            String noteId = noteNodeId(note.getId());
            nodes.add(new KnowledgeGraphResponse.Node(
                    noteId,
                    note.getTitle() == null || note.getTitle().isBlank() ? "无标题笔记" : note.getTitle(),
                    "note",
                    18 + Math.min(note.getTags().size() * 2, 10),
                    buildNoteDescription(note),
                    "/notebook/" + note.getNotebook().getId() + "?noteId=" + note.getId(),
                    note.getId(),
                    notebookName,
                    note.getUpdatedAt() == null ? null : DATE_TIME_FORMATTER.format(note.getUpdatedAt())
            ));

            links.add(new KnowledgeGraphResponse.Link(
                    notebookNodeId(note.getNotebook().getId()),
                    noteId,
                    "contains",
                    1,
                    notebookName + " / 包含"
            ));

            for (Tag tag : note.getTags()) {
                links.add(new KnowledgeGraphResponse.Link(
                        noteId,
                        tagNodeId(tag.getName()),
                        "tagged",
                        1,
                        "标记为 " + tag.getName()
                ));
            }
        }

        tagToNotes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> nodes.add(new KnowledgeGraphResponse.Node(
                        tagNodeId(entry.getKey()),
                        entry.getKey(),
                        "tag",
                        18 + Math.min(entry.getValue().size() * 3, 14),
                        "关联 " + entry.getValue().size() + " 篇笔记",
                        null,
                        null,
                        null,
                        null
                )));

        List<KnowledgeGraphResponse.Link> relatedNoteLinks = buildRelatedNoteLinks(tagToNotes, notes);
        links.addAll(relatedNoteLinks);

        KnowledgeGraphResponse.Summary summary = new KnowledgeGraphResponse.Summary(
                notebooks.size(),
                notes.size(),
                tagToNotes.size(),
                links.size(),
                relatedNoteLinks.size()
        );

        return new KnowledgeGraphResponse(summary, nodes, links);
    }

    private List<KnowledgeGraphResponse.Link> buildRelatedNoteLinks(Map<String, List<Note>> tagToNotes, List<Note> notes) {
        Map<String, Integer> pairWeights = new HashMap<>();
        Map<Long, Note> notesById = notes.stream().collect(Collectors.toMap(Note::getId, note -> note));

        for (List<Note> taggedNotes : tagToNotes.values()) {
            for (int index = 0; index < taggedNotes.size(); index += 1) {
                for (int otherIndex = index + 1; otherIndex < taggedNotes.size(); otherIndex += 1) {
                    Long leftId = taggedNotes.get(index).getId();
                    Long rightId = taggedNotes.get(otherIndex).getId();
                    String key = leftId < rightId ? leftId + ":" + rightId : rightId + ":" + leftId;
                    pairWeights.merge(key, 1, Integer::sum);
                }
            }
        }

        return pairWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(MAX_RELATED_NOTE_LINKS)
                .map(entry -> {
                    String[] parts = entry.getKey().split(":");
                    Long sourceId = Long.parseLong(parts[0]);
                    Long targetId = Long.parseLong(parts[1]);
                    Note sourceNote = notesById.get(sourceId);
                    Note targetNote = notesById.get(targetId);

                    if (sourceNote == null || targetNote == null) {
                        return null;
                    }

                    int weight = entry.getValue();
                    return new KnowledgeGraphResponse.Link(
                            noteNodeId(sourceId),
                            noteNodeId(targetId),
                            "related",
                            weight,
                            weight + " 个共同标签"
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String buildNoteDescription(Note note) {
        String notebookName = note.getNotebook() != null ? note.getNotebook().getName() : "";
        String preview = note.getContent() == null
                ? ""
                : note.getContent()
                .replaceAll("[#>*`\\-\\[\\]_]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (preview.length() > 88) {
            preview = preview.substring(0, 88) + "...";
        }

        int tagCount = note.getTags() == null ? 0 : note.getTags().size();
        String suffix = (notebookName.isBlank() ? "" : "所在笔记本：" + notebookName + " · ")
                + "标签数：" + tagCount;

        if (preview.isBlank()) {
            return suffix;
        }

        return preview + " · " + suffix;
    }

    private static String notebookNodeId(Long notebookId) {
        return "notebook-" + notebookId;
    }

    private static String noteNodeId(Long noteId) {
        return "note-" + noteId;
    }

    private static String tagNodeId(String tagName) {
        return "tag-" + tagName.toLowerCase(Locale.ROOT).replace(" ", "-");
    }
}
