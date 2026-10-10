package com.veridoc.ai.document.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.ai.common.error.ErrorCode;
import com.veridoc.ai.config.properties.StorageProperties;
import com.veridoc.ai.support.AbstractPostgresIntegrationTest;
import com.veridoc.ai.support.IngestionTestConfig;
import com.veridoc.ai.support.TestPdfs;

import tools.jackson.databind.ObjectMapper;

/**
 * Upload-path security: magic-byte enforcement, size ceilings and filename
 * handling. Anything the pipeline cannot trust is rejected synchronously with a
 * canonical error code before storage or queueing happens.
 */
@AutoConfigureMockMvc
@Import(IngestionTestConfig.class)
class FileSecurityIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageProperties storageProperties;

    @Test
    @DisplayName("bytes without the %PDF- marker are rejected synchronously as INVALID_PDF")
    void nonPdfBytesRejected() throws Exception {
        String token = tokenForNewUser();

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "evil.txt", "text/plain",
                                "this is not a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PDF.name()));
    }

    @Test
    @DisplayName("even a claimed application/pdf without the PDF magic is rejected")
    void fakePdfContentTypeRejected() throws Exception {
        String token = tokenForNewUser();

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "fake.pdf", "application/pdf",
                                "<html><script>alert(1)</script></html>"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PDF.name()));
    }

    @Test
    @DisplayName("a file above the configured ceiling is rejected as VALIDATION_FAILED")
    void oversizedFileRejected() throws Exception {
        String token = tokenForNewUser();

        byte[] huge = new byte[(int) storageProperties.maxFileSize() + 1];
        Arrays.fill(huge, (byte) 'x');

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "huge.pdf", "application/pdf", huge))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    @DisplayName("a traversal filename is sanitized away from any path separators")
    void traversalFilenameIsSanitized() throws Exception {
        String token = tokenForNewUser();
        byte[] pdf = TestPdfs.singlePage(TestPdfs.longEnoughParagraph());

        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "..\\..\\..\\etc\\passwd.pdf",
                                "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String displayName = objectMapper.readTree(body).get("filename").asText();
        assertThat(displayName)
                .doesNotContain("\\")
                .doesNotContain("/")
                .doesNotContain("..");
    }

    private String tokenForNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.RegisterRequest(
                                        email, PASSWORD, "File Security"))))
                .andExpect(status().isCreated());
        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.veridoc.ai.auth.api.dto.AuthDtos.LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).get("accessToken").asText();
    }
}