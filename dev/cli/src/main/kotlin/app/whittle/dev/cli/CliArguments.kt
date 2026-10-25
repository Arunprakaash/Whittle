package app.whittle.dev.cli

import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ReasoningEffort
import java.nio.file.Path
import java.nio.file.Paths

enum class ApprovalMode { ASK, ALWAYS, NEVER }

data class CliArguments(
    val providerId: ProviderId,
    val modelId: ModelId?,
    val prompt: String?,
    val listModels: Boolean,
    val approvalMode: ApprovalMode,
    val keyFile: Path,
    /** Null means "use the default from model-defaults.json". */
    val reasoningEffort: ReasoningEffort?,
    /** Where remembered facts are kept between sessions. */
    val memoryFile: Path,
    /** A suite of tasks to run instead of one prompt (see dev/cli/evals). */
    val evalSuite: Path? = null,
    /** Where an eval's results are written. */
    val reportFile: Path = Paths.get(DEFAULT_REPORT_FILE),
) {
    companion object {
        val USAGE = """
            |Usage: ./gradlew :dev:cli:run --args="--provider openai --model <model-id> --prompt '<text>'"
            |  --provider <id>      provider id from provider-catalog.json (default: $DEFAULT_PROVIDER)
            |  --model <id>         model id as listed by --list-models
            |  --prompt <text>      what to ask the agent
            |  --list-models        print the provider's models and exit
            |  --approve <mode>     ask | always | never  (default: ask)
            |  --reasoning <effort> none | low | medium | high  (default: model-defaults.json)
            |  --key-file <path>    properties file with "<provider-id>=<key>" (default: ~/$DEFAULT_KEY_FILE)
            |  --memory-file <path> what the agent remembers between sessions (default: ~/$DEFAULT_MEMORY_FILE)
            |  --eval <path>        run a suite of tasks instead of --prompt, e.g. dev/cli/evals/core.json
            |  --report <path>      where --eval writes its results (default: $DEFAULT_REPORT_FILE)
        """.trimMargin()

        private const val DEFAULT_PROVIDER = "openai"
        private const val DEFAULT_KEY_FILE = ".whittle/dev-keys.properties"
        private const val DEFAULT_MEMORY_FILE = ".whittle/cli-memory.json"
        private const val DEFAULT_REPORT_FILE = "build/eval-report.json"

        fun parse(args: Array<String>): Result<CliArguments> = runCatching {
            val options = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var index = 0
            while (index < args.size) {
                val name = args[index].removePrefix("--")
                if (name == "list-models") {
                    flags += name
                    index += 1
                } else {
                    options[name] = args.getOrNull(index + 1) ?: throw IllegalArgumentException("Missing value for --$name")
                    index += 2
                }
            }
            CliArguments(
                providerId = ProviderId(options["provider"] ?: DEFAULT_PROVIDER),
                modelId = options["model"]?.let(::ModelId),
                prompt = options["prompt"],
                listModels = "list-models" in flags,
                approvalMode = options["approve"]?.let { ApprovalMode.valueOf(it.uppercase()) } ?: ApprovalMode.ASK,
                keyFile = options["key-file"]?.let(Paths::get)
                    ?: Paths.get(System.getProperty("user.home"), DEFAULT_KEY_FILE),
                reasoningEffort = options["reasoning"]?.let { ReasoningEffort.valueOf(it.uppercase()) },
                memoryFile = options["memory-file"]?.let(Paths::get)
                    ?: Paths.get(System.getProperty("user.home"), DEFAULT_MEMORY_FILE),
                evalSuite = options["eval"]?.let(Paths::get),
                reportFile = options["report"]?.let(Paths::get) ?: Paths.get(DEFAULT_REPORT_FILE),
            )
        }
    }
}
