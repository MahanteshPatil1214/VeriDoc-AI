package com.veridoc.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.veridoc.ai.VeriDocApplication;
import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end rate limiting against a real Redis. Each test uses a fresh subject
 * (email / user id), so the fixed windows can be kept tight without tests
 * tripping over each other. All other suites keep the limiter disabled; only
 * this class exercises it.
 */
@SpringBootTest(classes = VeriDocApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class RateLimitIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String QUERY =
            "VeriDoc AI verifies claims against uploaded source documents";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17"))
            .withDatabaseName("veridoc")
            .withUsername("veridoc")
            .withPassword("veridoc");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.ai.google.genai.api-key", () -> "test-key-not-used");
        registry.add("spring.ai.google.genai.embedding.api-key", () -> "test-key-not-used");
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("veridoc.rate-limit.enabled", () -> "true");
        registry.add("veridoc.rate-limit.login", () -> "2/PT1M");
        registry.add("veridoc.rate-limit.upload", () -> "1/PT1H");
        registry.add("veridoc.rate-limit.chat", () -> "1/PT1H");
        registry.add("veridoc.rate-limit.processing", () -> "30/PT1H");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("login is throttled per account after two attempts")
    void loginRateLimited() throws Exception {
        String emailA = register("Alice");
        login(emailA, 200);
        login(emailA, 200);
        login(emailA, 429);

        // A different account is untouched: the window is per subject.
        String emailB = register("Bob");
        login(emailB, 200);
    }

    @Test
    @DisplayName("upload is throttled per user")
    void uploadRateLimited() throws Exception {
        String token = tokenForNewUser();

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "first.pdf", "application/pdf",
                                TestPdfs.singlePage(TestPdfs.longEnoughParagraph())))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "second.pdf", "application/pdf",
                                TestPdfs.singlePage("A completely different document body for testing.")))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMIT_EXCEEDED.name()));
    }

    @Test
    @DisplayName("chat is throttled per user after one question")
    void chatRateLimited() throws Exception {
        String token = tokenForNewUser();
        UUID docId = uploadAndAwaitReady(token);
        UUID conversationId = createConversation(token);
        attach(token, conversationId, docId);

        ask(token, conversationId, 200);
        ask(token, conversationId, 429);
    }

    private void ask(String token, UUID conversationId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.ChatRequest(QUERY))))
                .andExpect(status().is(expectedStatus));
    }

    private String register(String displayName) throws Exception {
        String email = displayName.toLowerCase(java.util.Locale.ROOT)
                + "-" + UUID.randomUUID() + "@example.com";
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, displayName))))
                .andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("register failed: " + result.getResponse().getStatus()
                    + " " + result.getResponse().getContentAsString());
        }
        return email;
    }

    private void login(String email, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().is(expectedStatus));
    }

    private String tokenForNewUser() throws Exception {
        String email = register("RateUser");
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private UUID uploadAndAwaitReady(String token) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "chat.pdf", "application/pdf",
                                TestPdfs.singlePage(TestPdfs.longEnoughParagraph())))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String statusBody = mockMvc.perform(get("/api/v1/documents/{id}", id)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (objectMapper.readTree(statusBody).get("status").asText().equals("READY")) {
                return id;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Document did not reach READY in time");
    }

    private UUID createConversation(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.CreateConversationRequest(
                                        "Rate limit"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private void attach(String token, UUID conversationId, UUID documentId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/conversations/{id}/documents", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.conversation.api.dto.ChatDtos.AttachDocumentsRequest(
                                        List.of(documentId)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("documents").get(0).get("id").asText())
                .isEqualTo(documentId.toString());
    }
}