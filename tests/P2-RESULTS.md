# P2 Test Results — Offline Research vertical slice (headless)

Date: 2026-10-31. Runner: host JVM (retrieval + prompt build identical to the
on-device `ResearchPipeline` code path), generation via llama.cpp host build
(`third_party/llama.cpp/build-host/bin/llama-completion`), model
Qwen3-0.6B Q4_K_M, ChatML template, offline (no network calls).

Test assets: `tests/P2Prompts.java` (retrieval + prompt build), prompts in
`research-test/prompts/`, raw outputs in `tests/p2-outputs/`,
machine-readable summary in `tests/p2-outputs/answers.json`.

## Per-test verdict

Legend — Retrieval: whether the retriever surfaced relevant evidence
(`has_evidence` from `research-test/prompts/report.json`). Grounded: answer
content is supported by the cited chunks. Citations: [n] markers used
correctly.

| Test | Retrieval | Grounded | Citations | Notes |
|------|-----------|----------|-----------|-------|
| T1-factual | yes (2 chunks, doc-02#c02 + doc-01#c02) | yes | correct | GGUF definition matches sources; one paraphrase blurs "CPU/GPU saat inferensi" phrasing, meaning preserved. |
| T2-explain | yes (3 chunks) | yes | correct | Pipeline (store model → assemble prompt → run on CPU/GPU → token-by-token → no data leaves device) matches doc-01#c02. **Answer truncated mid-sentence at the 400-token generation cap** (cut at "perangkat hanya mengirim pert"). |
| T3-compare | yes (4 chunks) | partial | correct but incomplete | Comparison section started correctly (on-device vs cloud, no server data), **cut off at the generation cap before any differences were listed** ("**Perbedaan:** 1." then nothing). Thinking trace did flag the internet-permission distinction from the sources, but it never made it into the visible answer. |
| T4-synthesize | yes (4 chunks) | yes | mixed | Synthesis of privacy ↔ on-device ↔ local model is faithful to doc-01/doc-03. Weakness: point 1 ("Privasi") restates point 2 almost verbatim without its own citation. Last sentence cut mid-phrase ("mempertahankan privasi" — likely truncated at cap). |
| T5-outofscope | decline by retriever (correct) | n/a (decline path) | n/a | Retriever correctly found no relevant evidence and emitted the standard decline message. The model, however, still generated a paragraph attempting to answer from near-miss context (NLP chunk) instead of echoing the decline verbatim — see Bugs/limitations. |
| T6-falsepremise | yes (retrieved GGUF chunks, but for the false premise "video compression") | yes — correctly refused | n/a | Model did not accept the false premise: "Buku sumber tidak menyertakan jawaban mengenai mengapa GGUF adalah format kompresi video." Correct behavior. |
| T7-en-factual | yes (4 chunks, doc-04 + doc-06#c05) | mostly | correct format, weak content | English question answered in English with correct [1]/[2]/[3] citation format. But the three claims are near-duplicates of each other ("RLHF aligns outputs with human preferences" restated three ways) rather than a real synthesis of the RLHF material — a symptom of the 0.6B model, not of the retrieval. |

All 7 tests exited 0.

## Performance (headless, host JVM/x86 VM — NOT representative of phone)

From `tests/p2-outputs/answers.json` (llama.cpp `common_perf_print`):

| Test | Prompt tokens | Prompt eval tok/s | Gen tokens | Generation tok/s |
|------|--------------|-------------------|-----------|------------------|
| T1-factual | 424 | 105.36 | 295 | 23.93 |
| T2-explain | 564 | 73.64 | 399 | 8.38 |
| T3-compare | 706 | 57.46 | 399 | 5.39 |
| T4-synthesize | 728 | 67.79 | 399 | 12.70 |
| T5-outofscope | 551 | 92.31 | 389 | 15.16 |
| T6-falsepremise | 428 | 74.95 | 199 | 13.24 |
| T7-en-factual | 676 | 82.55 | 309 | 17.89 |

Prompt eval scales inversely with prompt length as expected. Generation
tok/s varies 5.4–23.9 on this VM; on-device numbers will be much lower and
are pending device QA.

## Known limitations (honest list)

1. **Generation cap truncation.** A 400-token generation cap cut off the
   visible answer in T2 (mid-sentence), T3 (before the "differences" half
   of the comparison), and T4 (final phrase). T3 is the worst case: the
   most useful half of a COMPARE answer was lost. The raw model run
   includes a long `<think>` section that eats a large share of the
   budget before any visible text — Qwen3's thinking mode is not disabled
   by the current prompt.
2. **Thinking traces leak into storage but are correctly excluded from
   the answer body** in all files (raw `<think>` preserved in the saved
   outputs; answer text starts after `</think>`). Cosmetic, but it
   inflates token usage.
3. **Out-of-scope decline is only as good as the model.** The retriever
   correctly declined T5, but the model still produced a paragraph
   hedging around "how to make sourdough" instead of cleanly repeating
   the decline. On-device UX needs the app to surface the retriever's
   decline directly rather than trusting the model.
4. **Small-model synthesis depth.** T7 (and to a lesser degree T4) shows
   the classic 0.6B failure: three paraphrases of one claim instead of
   integrating distinct facts. Citations are formatted correctly even
   when content is thin.
5. **Host perf numbers are not phone numbers.** The tok/s above are from
   an x86 VM on a host CPU; arm64 on-device perf (load time, prefill,
   decode, peak memory) is entirely pending device QA by rezz.

## Bugs found

No code bugs proven from the P2 outputs. The truncation issue (limitation 1)
is a configuration/prompt-design issue (generation cap + Qwen3 thinking mode
not disabled), not a crash or incorrect output; it is listed here for
follow-up, not as a proven code defect.
