package com.letsblog.api.dto;

import java.util.List;

public record AiProofreadResponse(List<ProofreadIssue> issues) {
}
