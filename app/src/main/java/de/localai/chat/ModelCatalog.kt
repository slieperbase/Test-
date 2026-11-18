package de.localai.chat

/** Ein Modell, das direkt in der App heruntergeladen werden kann. */
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int,
    /** Kontextlänge (Tokens), für die die Datei gebaut wurde. */
    val maxTokens: Int,
    /** Empfohlener Arbeitsspeicher des Geräts in GB. */
    val minRamGb: Int,
    /** Gemma-Modelle erfordern einen Hugging-Face-Token mit akzeptierter Lizenz. */
    val needsToken: Boolean = false,
    val recommended: Boolean = false,
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"

    val models = listOf(
        CatalogModel(
            id = "qwen25-1.5b",
            name = "Qwen 2.5 · 1.5B",
            description = "Guter Allrounder, versteht und schreibt ordentlich Deutsch. Langes Gedächtnis.",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            url = "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            sizeMb = 1599, maxTokens = 4096, minRamGb = 6, recommended = true,
        ),
        CatalogModel(
            id = "qwen25-0.5b",
            name = "Qwen 2.5 · 0.5B",
            description = "Klein und schnell, gut für einfache Fragen. Kurzes Gedächtnis.",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            url = "$HF/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 547, maxTokens = 1280, minRamGb = 3,
        ),
        CatalogModel(
            id = "gemma3-1b",
            name = "Gemma 3 · 1B (Google)",
            description = "Sehr gutes kleines Modell von Google. Benötigt einen kostenlosen Hugging-Face-Token.",
            fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            url = "$HF/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            sizeMb = 555, maxTokens = 2048, minRamGb = 4, needsToken = true,
        ),
        CatalogModel(
            id = "deepseek-r1-1.5b",
            name = "DeepSeek R1 Distill · 1.5B",
            description = "Denkt sichtbar nach, bevor es antwortet – gut für Mathe und Logik. Antwortet oft auf Englisch.",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.task",
            url = "$HF/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.task",
            sizeMb = 1834, maxTokens = 4096, minRamGb = 6,
        ),
        CatalogModel(
            id = "phi4-mini",
            name = "Phi-4 mini (Microsoft)",
            description = "Deutlich klüger, aber groß und langsamer. Nur für leistungsstarke Handys.",
            fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
            url = "$HF/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
            sizeMb = 3910, maxTokens = 4096, minRamGb = 8,
        ),
        CatalogModel(
            id = "smollm-135m",
            name = "SmolLM · 135M",
            description = "Winzig, zum schnellen Ausprobieren. Nur Englisch und oft wenig sinnvoll.",
            fileName = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
            url = "$HF/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 167, maxTokens = 1280, minRamGb = 2,
        ),
    )

    fun byFileName(fileName: String) = models.firstOrNull { it.fileName == fileName }

    /** Kontextlänge für eine Modelldatei: aus dem Katalog, sonst aus „ekvNNNN“ im Namen. */
    fun maxTokensFor(fileName: String): Int {
        byFileName(fileName)?.let { return it.maxTokens }
        Regex("ekv(\\d+)").find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 2048
    }
}
