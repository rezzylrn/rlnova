package com.rezz.rlnova;

import java.util.List;

/**
 * Chat template per model (dari chatTemplate field models.json):
 *   - "chatml" : Qwen  (ChatML)
 *   - "llama3" : Llama 3.x
 *   - "gemma"  : Gemma 2/3
 *
 * Prompt dirangkai penuh di sisi Java — bridge murni text-in text-out.
 * add_special=false saat tokenize; BOS dimasukkan manual di sini
 * (format llama3 <|begin_of_text|>, ChatML <s> tidak wajib tapi aman
 * untuk Qwen/SmolLM2 karena add_special=false — Qwen tidak pakai BOS,
 * jadi untuk chatml kita TIDAK menambah BOS manual: parser model Qwen
 * mengharapkan template mentah tanpa BOS).
 */
public final class ChatTemplates {

    private static final String DEFAULT_SYSTEM_ID =
            "You are an AI assistant running fully offline on the user's device. "
            + "Answer in clear, concise English. If unsure, say so honestly.";

    public static String defaultSystemPrompt() { return DEFAULT_SYSTEM_ID; }

    /** Template yang didukung; selain ini fallback ke chatml. */
    public static boolean isSupported(String chatTemplate) {
        return "chatml".equals(chatTemplate) || "llama3".equals(chatTemplate)
                || "gemma".equals(chatTemplate);
    }

    /**
     * Rangkai prompt penuh dari riwayat pesan.
     * @param template "chatml" | "llama3" | "gemma"
     * @param system   system prompt (boleh kosong → default dipakai)
     * @param history  pasangan (role, content) TANPA system message;
     *                 role 1=user, 2=assistant
     * @param userTurn pesan user baru yang akan dijawab
     */
    public static String build(String chatTemplate, String system,
                               List<ChatDatabase.Message> history, String userTurn) {
        String sys = (system == null || system.trim().isEmpty())
                ? DEFAULT_SYSTEM_ID : system.trim();
        String t = chatTemplate == null ? "chatml" : chatTemplate;
        switch (t) {
            case "llama3": return buildLlama3(sys, history, userTurn);
            case "gemma":  return buildGemma(sys, history, userTurn);
            default:       return buildChatML(sys, history, userTurn);
        }
    }

    // ChatML (Qwen, SmolLM2)
    // <|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n...
    // Diakhiri dengan header asisten untuk generation prompt.
    private static String buildChatML(String sys, List<ChatDatabase.Message> history, String userTurn) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n");
        appendTurns(sb, history);
        sb.append("<|im_start|>user\n").append(userTurn).append("<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    // Llama 3.x
    // <|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n...<|eot_id|>
    private static String buildLlama3(String sys, List<ChatDatabase.Message> history, String userTurn) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
          .append(sys).append("<|eot_id|>");
        for (ChatDatabase.Message m : history) {
            String role = m.role == ChatDatabase.ROLE_ASSISTANT ? "assistant" : "user";
            sb.append("<|start_header_id|>").append(role)
              .append("<|end_header_id|>\n\n").append(m.content).append("<|eot_id|>");
        }
        sb.append("<|start_header_id|>user<|end_header_id|>\n\n")
          .append(userTurn).append("<|eot_id|>");
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
        return sb.toString();
    }

    // Gemma 2/3 — <start_of_turn>user ... <end_of_turn>\n<start_of_turn>model
    // System prompt digabung ke user turn pertama (Gemma tidak punya slot system).
    private static String buildGemma(String sys, List<ChatDatabase.Message> history, String userTurn) {
        StringBuilder sb = new StringBuilder("<bos><start_of_turn>user\n").append(sys);
        boolean firstUser = true;
        for (ChatDatabase.Message m : history) {
            if (m.role == ChatDatabase.ROLE_ASSISTANT) {
                sb.append("<end_of_turn>\n<start_of_turn>model\n").append(m.content);
            } else {
                sb.append(firstUser ? "\n" : "<end_of_turn>\n<start_of_turn>user\n").append(m.content);
            }
            firstUser = false;
        }
        // pesan user baru
        sb.append(firstUser ? "\n" : "<end_of_turn>\n<start_of_turn>user\n").append(userTurn);
        sb.append("<end_of_turn>\n<start_of_turn>model\n");
        return sb.toString();
    }

    private static void appendTurns(StringBuilder sb, List<ChatDatabase.Message> history) {
        for (ChatDatabase.Message m : history) {
            String role = m.role == ChatDatabase.ROLE_ASSISTANT ? "assistant" : "user";
            sb.append("<|im_start|>").append(role).append("\n")
              .append(m.content).append("<|im_end|>\n");
        }
    }

    private ChatTemplates() { }
}
