package com.letsblog.ai.dto;

/** provider/modelともに空/null時はそのステップの上書きを解除する(issue #1211)。 */
public record SelectReviewStepModelRequest(String provider, String model) {
}
