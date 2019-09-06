package de.nielsfalk.ktor.swagger

import de.nielsfalk.ktor.swagger.version.shared.Property
import kotlin.reflect.KClass

@Target(AnnotationTarget.PROPERTY)
annotation class DefaultValue(
    val value: String
)

@Target(AnnotationTarget.PROPERTY)
annotation class Description(
    val description: String
)

@Target(AnnotationTarget.PROPERTY)
annotation class Ignore

@Target(AnnotationTarget.PROPERTY)
annotation class Type(
    val generator: KClass<out PropertyGenerator>
)

interface PropertyGenerator {
    fun generateProperty(): Property
}
