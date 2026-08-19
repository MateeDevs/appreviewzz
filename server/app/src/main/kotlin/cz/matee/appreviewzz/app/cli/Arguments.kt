package cz.matee.appreviewzz.app.cli

/** Příkaz nebo volba, které CLI nezná. Reakcí je nápověda, ne stack trace. */
class UsageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Příkaz byl srozumitelný, ale nedal se provést — neexistující organizace, neplatný klíč. */
class CommandException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Volby jednoho příkazu ve tvaru `--volba hodnota` nebo `--volba=hodnota`.
 *
 * Seznam známých voleb je povinný parametr [parse] schválně: seed CLI zakládá appky a nahrává
 * klíče, a překlep v `--gp-package` by jinak tiše založil appku bez Androidu. Neznámá volba
 * je proto chyba, ne ignorovaný token.
 */
class Arguments private constructor(
    private val values: Map<String, String>,
) {
    fun required(name: String): String = optional(name) ?: throw UsageException("Chybí povinná volba --$name")

    fun optional(name: String): String? = values[name]?.takeIf { it.isNotBlank() }

    /** `--volba true|false` (taky `ano|ne`); přepínač bez hodnoty by se pral s `--volba hodnota`. */
    fun boolean(name: String): Boolean? =
        optional(name)?.let { raw ->
            when (raw.lowercase()) {
                "true", "ano", "yes", "1" -> true
                "false", "ne", "no", "0" -> false
                else -> throw UsageException("Volba --$name čeká true nebo false, dostala '$raw'")
            }
        }

    fun int(name: String): Int? =
        optional(name)?.let { raw ->
            raw.toIntOrNull() ?: throw UsageException("Volba --$name čeká celé číslo, dostala '$raw'")
        }

    companion object {
        fun parse(
            tokens: List<String>,
            known: Set<String>,
        ): Arguments {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                if (!token.startsWith(PREFIX)) {
                    throw UsageException("Nečekaný argument '$token' — volby se zadávají jako --volba hodnota")
                }

                val raw = token.removePrefix(PREFIX)
                val name = raw.substringBefore('=')
                val value =
                    when {
                        raw.contains('=') -> raw.substringAfter('=')
                        // Následující token je hodnota jen tehdy, když sám není další volbou —
                        // jinak by `--org --type gp` tiše uložilo do --org řetězec "--type".
                        else ->
                            tokens.getOrNull(index + 1)?.takeUnless { it.startsWith(PREFIX) }?.also { index++ }
                                ?: throw UsageException("Volba --$name čeká hodnotu")
                    }

                if (name !in known) {
                    throw UsageException("Neznámá volba --$name; příkaz zná: ${known.sorted().joinToString { "--$it" }}")
                }
                if (values.put(name, value) != null) {
                    throw UsageException("Volba --$name je zadaná dvakrát")
                }
                index++
            }
            return Arguments(values)
        }

        private const val PREFIX = "--"
    }
}
