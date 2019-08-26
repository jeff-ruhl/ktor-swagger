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
annotation class Schema(
    val generator: KClass<out SchemaGenerator>
)

interface SchemaGenerator {
    fun generateSchema(): Property
}