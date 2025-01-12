package de.nielsfalk.ktor.swagger

import de.nielsfalk.ktor.swagger.version.shared.CommonBase
import de.nielsfalk.ktor.swagger.version.shared.Group
import de.nielsfalk.ktor.swagger.version.shared.ModelName
import de.nielsfalk.ktor.swagger.version.shared.OperationBase
import de.nielsfalk.ktor.swagger.version.shared.ParameterBase
import de.nielsfalk.ktor.swagger.version.shared.ParameterInputType
import de.nielsfalk.ktor.swagger.version.v2.Swagger
import de.nielsfalk.ktor.swagger.version.v3.OpenApi
import io.ktor.application.Application
import io.ktor.application.ApplicationFeature
import io.ktor.client.call.TypeInfo
import io.ktor.client.call.typeInfo
import io.ktor.http.HttpMethod
import io.ktor.locations.Location
import io.ktor.util.AttributeKey
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import de.nielsfalk.ktor.swagger.version.v2.Operation as OperationV2
import de.nielsfalk.ktor.swagger.version.v2.Parameter as ParameterV2
import de.nielsfalk.ktor.swagger.version.v2.Response as ResponseV2
import de.nielsfalk.ktor.swagger.version.v3.Operation as OperationV3
import de.nielsfalk.ktor.swagger.version.v3.Parameter as ParameterV3
import de.nielsfalk.ktor.swagger.version.v3.Response as ResponseV3

class SwaggerSupport(
    val swagger: Swagger?,
    private val swaggerCustomization: Metadata.(HttpMethod) -> Metadata,
    val openApi: OpenApi?,
    private val openApiCustomization: Metadata.(HttpMethod) -> Metadata
) {
    companion object Feature : ApplicationFeature<Application, SwaggerUiConfiguration, SwaggerSupport> {
        internal val swaggerVariation = SpecVariation("#/definitions/", ResponseV2, OperationV2, ParameterV2)
        internal val openApiVariation = SpecVariation("#/components/schemas/", ResponseV3, OperationV3, ParameterV3)

        override val key = AttributeKey<SwaggerSupport>("SwaggerSupport")

        override fun install(pipeline: Application, configure: SwaggerUiConfiguration.() -> Unit): SwaggerSupport {
            val (swagger,
                openApi,
                swaggerConfig,
                openApiConfig
            ) = SwaggerUiConfiguration().apply(configure)
            return SwaggerSupport(swagger, swaggerConfig, openApi, openApiConfig)
        }
    }

    private val commons: Collection<CommonBase> =
        listOfNotNull(swagger, openApi)

    private val variations: Collection<BaseWithVariation<out CommonBase>>
        get() = commons.map {
            when (it) {
                is Swagger -> SwaggerBaseWithVariation(
                    it,
                    swaggerCustomization,
                    swaggerVariation
                )
                is OpenApi -> OpenApiBaseWithVariation(
                    it,
                    openApiCustomization,
                    openApiVariation
                )
                else -> throw IllegalStateException("Must be of type ${Swagger::class.simpleName} or ${OpenApi::class.simpleName}")
            }
        }

    inline fun <reified LOCATION : Any, reified ENTITY_TYPE : Any> Metadata.apply(method: HttpMethod) {
        apply(LOCATION::class, typeInfo<ENTITY_TYPE>(), method)
    }

    @PublishedApi
    internal fun Metadata.apply(locationClass: KClass<*>, bodyTypeInfo: TypeInfo, method: HttpMethod) {
        variations.forEach {
            it.apply { metaDataConfiguration(method).apply(locationClass, bodyTypeInfo, method) }
        }
    }
}

private class SwaggerBaseWithVariation(
    base: Swagger,
    metaDataConfiguration: Metadata.(HttpMethod) -> Metadata,
    variation: SpecVariation
) : BaseWithVariation<Swagger>(base, metaDataConfiguration, variation) {

    override val schemaHolder: MutableMap<ModelName, Any>
        get() = base.definitions

    override fun addDefinition(name: String, schema: Any) {
        base.definitions.putIfAbsent(name, schema)
    }
}

private class OpenApiBaseWithVariation(
    base: OpenApi,
    metaDataConfiguration: Metadata.(HttpMethod) -> Metadata,
    variation: SpecVariation
) : BaseWithVariation<OpenApi>(base, metaDataConfiguration, variation) {
    override val schemaHolder: MutableMap<ModelName, Any>
        get() = base.components.schemas

    override fun addDefinition(name: String, schema: Any) {
        base.components.schemas.putIfAbsent(name, schema)
    }
}

private abstract class BaseWithVariation<B : CommonBase>(
    val base: B,
    val metaDataConfiguration: Metadata.(HttpMethod) -> Metadata,
    val variation: SpecVariation
) {
    abstract val schemaHolder: MutableMap<ModelName, Any>

    abstract fun addDefinition(name: String, schema: Any)

    fun addDefinition(typeInfo: TypeInfo) {
        if (typeInfo.type != Unit::class) {
            val accruedNewDefinitions = mutableListOf<TypeInfo>()
            schemaHolder
                .computeIfAbsent(typeInfo.modelName()) {
                    val modelWithAdditionalDefinitions = variation {
                        createModelData(typeInfo)
                    }
                    accruedNewDefinitions.addAll(modelWithAdditionalDefinitions.second)
                    modelWithAdditionalDefinitions.first
                }

            accruedNewDefinitions.forEach { addDefinition(it) }
        }
    }

    fun addDefinitions(kClasses: Collection<TypeInfo>) =
        kClasses.forEach {
            addDefinition(it)
        }

    fun <LOCATION : Any> Metadata.applyOperations(
        location: Location,
        group: Group?,
        method: HttpMethod,
        locationType: KClass<LOCATION>,
        bodyType: BodyType
    ) {
        val path = when (pathPrefix) {
            null -> location.path
            else -> "$pathPrefix${location.path.trimStart('/')}"
        }

        if (bodyType is BodyFromReflection && bodyType.typeInfo.type != Unit::class) {
            addDefinition(bodyType.typeInfo)
        }

        fun createOperation(): OperationBase {
            val responses = responses.map { codeResponse ->
                codeResponse.responseTypes.forEach {
                    if (it is JsonResponseFromReflection) {
                        addDefinition(it.type)
                    }
                }

                val response = variation.reponseCreator.create(codeResponse)

                codeResponse.statusCode.value.toString() to response
            }.toMap().filterNullValues()

            val parameters = mutableListOf<ParameterBase>().apply {
                variation {
                    if ((bodyType as? BodyFromReflection)?.typeInfo?.type != Unit::class) {
                        add(bodyType.bodyParameter())
                    }
                    addAll(locationType.memberProperties.map {
                        it.toParameter(path).let {
                            addDefinitions(it.second)
                            it.first
                        }
                    })
                    fun KClass<*>.processToParameters(parameterType: ParameterInputType) {
                        addAll(memberProperties.map {
                            it.toParameter(path, parameterType).let {
                                addDefinitions(it.second)
                                it.first
                            }
                        })
                    }
                    parameters.forEach { it.processToParameters(ParameterInputType.query) }
                    headers.forEach { it.processToParameters(ParameterInputType.header) }
                }
            }

            return variation.operationCreator.create(
                this,
                responses,
                parameters,
                location,
                group,
                method,
                bodyExamples,
                operationId
            )
        }

        base.paths
            .getOrPut(path) { mutableMapOf() }
            .put(
                method.value.toLowerCase(),
                createOperation()
            )
    }

    private fun <K : Any, V> Map<K, V?>.filterNullValues(): Map<K, V> {
        val destination = mutableListOf<Pair<K, V>>()
        forEach {
            val valueSaved = it.value
            if (valueSaved != null) {
                destination.add(it.key to valueSaved)
            }
        }
        return destination.toMap()
    }

    private fun Metadata.createBodyType(typeInfo: TypeInfo): BodyType = when {
        bodySchema != null -> {
            BodyFromSchema(
                    name = bodySchema.name ?: typeInfo.modelName(),
                    examples = bodyExamples
            )
        }
        typeInfo.type == String::class -> BodyFromString(bodyExamples)
        else -> BodyFromReflection(typeInfo, bodyExamples)
    }

    private fun Metadata.requireMethodSupportsBody(method: HttpMethod) =
        require(!(methodForbidsBody.contains(method) && bodySchema != null)) {
            "Method type $method does not support a body parameter."
        }

    internal fun Metadata.apply(locationClass: KClass<*>, bodyTypeInfo: TypeInfo, method: HttpMethod) {
        requireMethodSupportsBody(method)
        val bodyType = createBodyType(bodyTypeInfo)
        val clazz = locationClass.java
        val location = clazz.getAnnotation(Location::class.java)
        val tags = clazz.getAnnotation(Group::class.java)

        applyOperations(location, tags, method, locationClass, bodyType)
    }

    companion object {
        /**
         * The [HttpMethod] types that don't support having a HTTP body element.
         */
        private val methodForbidsBody = setOf(HttpMethod.Get, HttpMethod.Delete)
    }
}

data class SwaggerUiConfiguration(
    var swagger: Swagger? = null,
    var openApi: OpenApi? = null,
    /**
     * Customization mutation applied to every [Metadata] processed for the swagger.json
     */
    var swaggerCustomization: Metadata.(HttpMethod) -> Metadata = { this },
    /**
     * Customization mutation applied to every [Metadata] processed for the openapi.json
     */
    var openApiCustomization: Metadata.(HttpMethod) -> Metadata = { this }
)
