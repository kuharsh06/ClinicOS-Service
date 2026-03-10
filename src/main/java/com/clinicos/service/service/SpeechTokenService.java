package com.clinicos.service.service;

import com.clinicos.service.dto.response.SpeechTokenResponse;
import com.clinicos.service.entity.OrgMember;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.OrgMemberRepository;
import com.clinicos.service.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Provides speech-to-text API tokens for client-side usage.
 *
 * Current: returns a permanent Deepgram API key.
 * Future:  call Deepgram's temporary keys API (POST /v1/keys/{projectId}/temporary)
 *          to issue short-lived, scoped tokens per request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpeechTokenService {

    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;

    @Value("${clinicos.speech.enabled:false}")
    private boolean speechEnabled;

    @Value("${clinicos.speech.api-key:}")
    private String apiKey;

    public SpeechTokenResponse getToken(String orgUuid, Integer userId) {
        if (!speechEnabled || apiKey.isBlank()) {
            throw new BusinessException("SPEECH_DISABLED", "Speech-to-text is not enabled");
        }

        // Validate org exists and user belongs to it
        var org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        OrgMember member = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userId.toString()));

        if (!member.getIsActive() || member.getDeletedAt() != null) {
            throw new BusinessException("MEMBER_INACTIVE", "You are not an active member of this organization");
        }

        log.info("Speech token issued to user {} in org {}", userId, orgUuid);

        // TODO: Replace with Deepgram temporary keys API for short-lived tokens
        //   POST https://api.deepgram.com/v1/keys/{projectId}/temporary
        //   Body: { "time_to_live": 60 }
        //   Returns: { "key": "short-lived-key", "expires_at": "..." }
        //   Then set expiresAt in the response.
        return SpeechTokenResponse.builder()
                .token(apiKey)
                .provider("deepgram")
                .build();
    }
}
