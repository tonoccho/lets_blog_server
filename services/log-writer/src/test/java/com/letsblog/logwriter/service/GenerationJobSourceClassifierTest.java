package com.letsblog.logwriter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ジョブ種別 → ソース種別の対応表(issue #1481)。 */
class GenerationJobSourceClassifierTest {

    @Test
    void image_generationはAI_JOBに分類する() {
        assertEquals("AI_JOB", GenerationJobSourceClassifier.classify("image_generation"));
    }

    @Test
    void comfyui_checkpoint_downloadはAI_JOBに分類する() {
        assertEquals("AI_JOB", GenerationJobSourceClassifier.classify("comfyui_checkpoint_download"));
    }

    @Test
    void media_garbage_collection_deleteはSYSTEM_JOBに分類する() {
        assertEquals("SYSTEM_JOB", GenerationJobSourceClassifier.classify("media_garbage_collection_delete"));
    }

    @Test
    void 未知の種別は例外にせずAI_JOBに分類する() {
        assertEquals("AI_JOB", GenerationJobSourceClassifier.classify("something_new"));
        assertEquals("AI_JOB", GenerationJobSourceClassifier.classify("draft"));
    }

    @Test
    void nullと空文字は例外にせずAI_JOBに分類する() {
        assertEquals("AI_JOB", GenerationJobSourceClassifier.classify(null));
        assertEquals("AI_JOB", GenerationJobSourceClassifier.classify(""));
    }

    @Test
    void isFetchedFromJobsは_未指定_AI_JOB_SYSTEM_JOBのときだけ真() {
        assertTrue(GenerationJobSourceClassifier.isJobSourceRequest(null));
        assertTrue(GenerationJobSourceClassifier.isJobSourceRequest(" "));
        assertTrue(GenerationJobSourceClassifier.isJobSourceRequest("AI_JOB"));
        assertTrue(GenerationJobSourceClassifier.isJobSourceRequest("system_job"));
        assertFalse(GenerationJobSourceClassifier.isJobSourceRequest("OPERATION"));
        assertFalse(GenerationJobSourceClassifier.isJobSourceRequest("AUDIT"));
    }

    @Test
    void matchesは要求種別が空なら全件_指定ありなら一致するものだけ() {
        assertTrue(GenerationJobSourceClassifier.matches(null, "media_garbage_collection_delete"));
        assertTrue(GenerationJobSourceClassifier.matches("", "image_generation"));
        assertTrue(GenerationJobSourceClassifier.matches("ai_job", "image_generation"));
        assertFalse(GenerationJobSourceClassifier.matches("AI_JOB", "media_garbage_collection_delete"));
        assertTrue(GenerationJobSourceClassifier.matches("SYSTEM_JOB", "media_garbage_collection_delete"));
        assertFalse(GenerationJobSourceClassifier.matches("SYSTEM_JOB", "image_generation"));
    }
}
