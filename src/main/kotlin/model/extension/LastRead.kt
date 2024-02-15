package model.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

@Serializable
abstract class LastRead(
    @Serializable(with = InstantSerializer::class) var lastRead: Instant = Instant.now()
) : Comparable<LastRead> {
    override fun compareTo(other: LastRead) = compareValuesBy(this, other,
        { it.lastRead },
        { it.lastRead }
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = Instant::class)
object InstantSerializer : KSerializer<Instant> {
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
