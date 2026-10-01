package org.lia.accessibility.agent

import java.text.Normalizer
import java.util.Locale
import org.lia.accessibility.security.ActionRisk

object EffectiveToolRiskPolicy {
    private val sensitiveClickTokens = setOf(
        "enviar",
        "send",
        "llamar",
        "call",
        "publicar",
        "publish",
        "post",
        "compartir",
        "share"
    )

    private val irreversibleClickTokens = setOf(
        "pagar",
        "pay",
        "comprar",
        "purchase",
        "buy",
        "transferir",
        "transfer",
        "eliminar",
        "delete",
        "borrar cuenta",
        "delete account",
        "confirmar compra",
        "place order",
        "confirm order"
    )

    private val sensitiveFieldTokens = setOf(
        "contrasena",
        "password",
        "pin",
        "cvv",
        "codigo de seguridad",
        "security code",
        "otp",
        "codigo de verificacion",
        "verification code"
    )

    fun effectiveRisk(call: LiaToolCall): ActionRisk {
        val contextual = when (call.name) {
            "click" -> clickRisk(call.textArgument("target"))
            "set_text" -> fieldRisk(call.textArgument("target"))
            else -> ActionRisk.ROUTINE
        }

        return maxRisk(call.risk, contextual)
    }

    private fun clickRisk(target: String?): ActionRisk {
        val normalized = normalize(target.orEmpty())

        if (irreversibleClickTokens.any { normalized.contains(it) }) {
            return ActionRisk.IRREVERSIBLE
        }

        if (sensitiveClickTokens.any { normalized.contains(it) }) {
            return ActionRisk.SENSITIVE
        }

        return ActionRisk.ROUTINE
    }

    private fun fieldRisk(target: String?): ActionRisk {
        val normalized = normalize(target.orEmpty())

        return if (sensitiveFieldTokens.any { normalized.contains(it) }) {
            ActionRisk.SENSITIVE
        } else {
            ActionRisk.ROUTINE
        }
    }

    private fun maxRisk(a: ActionRisk, b: ActionRisk): ActionRisk =
        if (rank(a) >= rank(b)) a else b

    private fun rank(risk: ActionRisk): Int =
        when (risk) {
            ActionRisk.ROUTINE -> 0
            ActionRisk.SENSITIVE -> 1
            ActionRisk.IRREVERSIBLE -> 2
        }

    private fun LiaToolCall.textArgument(name: String): String? =
        (arguments[name] as? ToolValue.Text)?.value

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
}
