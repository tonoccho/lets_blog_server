package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

/** X の認可画面から戻ったときの state と認可コード(issue #1574)。 */
public record XCallbackRequest(@NotBlank String state, @NotBlank String code) {

    @Override
    public String toString() {
        return "XCallbackRequest[state=" + state + "]";
    }
}
