package com.letsblog.ai.dto;

import java.util.List;

public record AiProofreadResponse(List<ProofreadIssue> issues) {
}
