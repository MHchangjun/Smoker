package com.song.inspection

enum class InspectionRule(val id: String) {
    UNUSED_SYMBOL("UnusedSymbol"),
    UNUSED_VARIABLE("UnusedVariable"),
    UNUSED_EXPRESSION("UnusedExpression"),
    CAN_BE_VAL("CanBeVal"),
    REDUNDANT_SEMICOLON("RedundantSemicolon"),
    REDUNDANT_UNIT_RETURN_TYPE("RedundantUnitReturnType"),
    REMOVE_EMPTY_CLASS_BODY("RemoveEmptyClassBody");

    companion object {
        private val BY_ID: Map<String, InspectionRule> = entries.associateBy { it.id }

        fun fromId(id: String): InspectionRule? = BY_ID[id]

        val ALL_IDS: List<String> = entries.map { it.id }
    }
}
