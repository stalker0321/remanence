package dev.hryshyn.rv01probe.probe

/** Serializes only fixed enum values; it intentionally has no free-form fields. */
object EvidenceJson {
    fun encode(records: List<EvidenceRecord>): String = records.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ",",
    ) { record ->
        """
        {"tuple":"${record.tuple.name}","candidate":"${record.candidate.name}","result":"${record.result.name}","capability":"${record.capability.name}","placement":"${record.placement.name}","backupEligibility":"${record.backupEligibility.name}","screenLock":"${record.screenLock.name}","e2ee":"${record.e2ee.name}","restorePath":"${record.restorePath.name}","evidenceClass":"${record.evidenceClass.name}"}
        """.trimIndent()
    }
}
