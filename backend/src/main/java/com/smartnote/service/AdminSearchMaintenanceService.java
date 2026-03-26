package com.smartnote.service;

import com.smartnote.dto.AdminSearchMaintenanceLastRunResponse;
import com.smartnote.dto.AdminSearchMaintenanceResponse;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.TagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AdminSearchMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(AdminSearchMaintenanceService.class);
    private static final String TRASH_STATUS = "TRASH";
    private static final String DEFAULT_STRATEGY = "标题、正文与标签名模糊匹配";
    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final NoteRepository noteRepository;
    private final NotebookRepository notebookRepository;
    private final TagRepository tagRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final AtomicReference<MaintenanceRunState> lastRunState = new AtomicReference<>(
            new MaintenanceRunState(
                    STATUS_IDLE,
                    null,
                    null,
                    null,
                    "尚未执行搜索维护操作"
            )
    );

    public AdminSearchMaintenanceService(
            NoteRepository noteRepository,
            NotebookRepository notebookRepository,
            TagRepository tagRepository,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource
    ) {
        this.noteRepository = noteRepository;
        this.notebookRepository = notebookRepository;
        this.tagRepository = tagRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Transactional(readOnly = true)
    public AdminSearchMaintenanceResponse getOverview() {
        return new AdminSearchMaintenanceResponse(
                DEFAULT_STRATEGY,
                noteRepository.countByStatusNot(TRASH_STATUS),
                notebookRepository.countByStatusNot(TRASH_STATUS),
                tagRepository.count(),
                countSearchableNoteTagLinks(),
                lastRunState.get().toResponse()
        );
    }

    public AdminSearchMaintenanceResponse runMaintenance(String rawOperation) {
        MaintenanceOperation operation = MaintenanceOperation.from(rawOperation);
        LocalDateTime startedAt = LocalDateTime.now();

        try {
            executeStatements(operation.statements());
            LocalDateTime finishedAt = LocalDateTime.now();
            String message = switch (operation) {
                case ANALYZE -> "搜索相关表统计信息已刷新";
                case VACUUM -> "搜索相关表已完成 VACUUM ANALYZE";
                case REINDEX -> "搜索相关表索引已完成重建";
            };
            lastRunState.set(new MaintenanceRunState(
                    STATUS_SUCCESS,
                    operation.value,
                    startedAt,
                    finishedAt,
                    message
            ));
            return getOverview();
        } catch (SQLException exception) {
            LocalDateTime finishedAt = LocalDateTime.now();
            String message = operation.displayName + " 执行失败：" + simplifyMessage(exception);
            log.warn("Failed to execute search maintenance operation {}", operation.value, exception);
            lastRunState.set(new MaintenanceRunState(
                    STATUS_FAILED,
                    operation.value,
                    startedAt,
                    finishedAt,
                    message
            ));
            throw new IllegalArgumentException(message);
        }
    }

    private long countSearchableNoteTagLinks() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM note_tags nt
                JOIN notes n ON n.id = nt.note_id
                WHERE n.status <> ?
                """,
                Long.class,
                TRASH_STATUS
        );
        return count == null ? 0L : count;
    }

    private void executeStatements(List<String> statements) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);

            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private String simplifyMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "数据库未返回更多信息";
        }
        return message;
    }

    private record MaintenanceRunState(
            String status,
            String operation,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String message
    ) {
        private AdminSearchMaintenanceLastRunResponse toResponse() {
            return new AdminSearchMaintenanceLastRunResponse(
                    status,
                    operation,
                    startedAt,
                    finishedAt,
                    message
            );
        }
    }

    private enum MaintenanceOperation {
        ANALYZE(
                "analyze",
                "ANALYZE",
                List.of(
                        "ANALYZE note_tags",
                        "ANALYZE notes",
                        "ANALYZE notebooks",
                        "ANALYZE tags"
                )
        ),
        VACUUM(
                "vacuum",
                "VACUUM ANALYZE",
                List.of(
                        "VACUUM ANALYZE note_tags",
                        "VACUUM ANALYZE notes",
                        "VACUUM ANALYZE notebooks",
                        "VACUUM ANALYZE tags"
                )
        ),
        REINDEX(
                "reindex",
                "REINDEX",
                List.of(
                        "REINDEX TABLE note_tags",
                        "REINDEX TABLE notes",
                        "REINDEX TABLE notebooks",
                        "REINDEX TABLE tags"
                )
        );

        private final String value;
        private final String displayName;
        private final List<String> sqlStatements;

        MaintenanceOperation(String value, String displayName, List<String> sqlStatements) {
            this.value = value;
            this.displayName = displayName;
            this.sqlStatements = sqlStatements;
        }

        private List<String> statements() {
            return sqlStatements;
        }

        private static MaintenanceOperation from(String rawValue) {
            String normalized = Objects.requireNonNullElse(rawValue, "").trim().toLowerCase(Locale.ROOT);
            for (MaintenanceOperation operation : values()) {
                if (operation.value.equals(normalized)) {
                    return operation;
                }
            }
            throw new IllegalArgumentException("Unsupported search maintenance operation");
        }
    }
}
