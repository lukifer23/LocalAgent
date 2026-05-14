package com.localagent.bridge

data class ChatUiState(
    val sessionId: String?,
    val lines: List<ChatLine>,
    val pendingApproval: ApprovalRequest? = null,
)

data class ApprovalRequest(
    val promptId: String,
    val command: String,
)

data class ChatLine(
    val id: String,
    val role: Role,
    val text: String,
    val approvalPromptId: String? = null,
)

enum class Role {
    User,
    Assistant,
    Tool,
    Meta,
}
